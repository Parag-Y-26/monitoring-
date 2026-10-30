[CmdletBinding()]
param(
    [string]$Python = 'python',
    [string]$VisionFixture = '',
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runDirectory = Join-Path $repoRoot ('artifacts\pipeline-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$result = [ordered]@{ status = 'RUNNING'; started_utc = [DateTime]::UtcNow.ToString('o'); commit = ''; android_environment = 'API 36 managed emulator, x86_64'; stages = @{} }
$previousJava = $env:JAVA_HOME

function Invoke-Logged([string]$Executable, [string[]]$CommandArguments, [string]$LogName) {
    $log = Join-Path $runDirectory $LogName
    & $Executable @CommandArguments *> $log
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $log -Tail 30 | Write-Host
        throw "Command failed. See $log"
    }
}
function Read-JUnit([System.IO.FileInfo[]]$Reports) {
    if (!$Reports -or $Reports.Count -eq 0) { throw 'JUnit reports are missing.' }
    $total = 0; $skipped = 0
    foreach ($report in $Reports) {
        [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
        $cases = $xml.SelectNodes('//testcase')
        if ($cases.Count -eq 0 -or $xml.SelectNodes('//failure|//error').Count -gt 0) { throw "Empty or failing report: $($report.FullName)" }
        $total += $cases.Count
        $skipped += $xml.SelectNodes('//testcase/skipped').Count
    }
    if ($skipped -gt 0) { throw "Incomplete test execution: $skipped skipped tests." }
    return @{ tests = $total; skipped = $skipped }
}
function Source-Evidence {
    $paths = git ls-files --cached --others --exclude-standard -- app/src core/src tools tests gradle app/build.gradle.kts core/build.gradle.kts build.gradle.kts settings.gradle.kts gradle.properties app/gradle.lockfile
    $inputs = @($paths | Sort-Object -Unique | ForEach-Object {
        if (Test-Path -LiteralPath $_ -PathType Leaf) { [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } }
    })
    $bytes = [Text.Encoding]::UTF8.GetBytes(($inputs | ConvertTo-Json -Depth 3 -Compress))
    $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    return @{ sha256 = $digest; inputs = $inputs }
}

Push-Location $repoRoot
try {
    $result.commit = (git rev-parse HEAD).Trim()
    $result.worktree_dirty = [bool](git status --porcelain)
    $source = Source-Evidence
    $result.source_inputs_sha256 = $source.sha256
    $source.inputs | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $runDirectory 'source-inputs.json') -Encoding utf8
    if (!$JavaHome) { $JavaHome = 'C:\Program Files\Android\Android Studio\jbr' }
    if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Pass -JavaHome with a supported JDK.' }
    $env:JAVA_HOME = $JavaHome
    if (!$VisionFixture) { $VisionFixture = Join-Path $repoRoot 'artifacts\modelprep\calibration\000000579158.jpg' }
    $fixture = Get-Item -LiteralPath $VisionFixture
    $fixtureHash = (Get-FileHash -LiteralPath $fixture.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($fixtureHash -ne '6dc2f95bb3c64811329326a0f6f5855ae59940652534ad77ef047536f55fb1ff') {
        throw 'Provide the pinned official COCO 000000579158.jpg fixture; see docs/PIPELINE_TESTS.md.'
    }
    $assetDirectory = Join-Path $runDirectory 'vision-fixture'
    New-Item -ItemType Directory -Path $assetDirectory | Out-Null
    Copy-Item -LiteralPath $fixture.FullName -Destination (Join-Path $assetDirectory 'airport.jpg')
    $result.vision_fixture_sha256 = $fixtureHash
    Invoke-Logged $Python @('-m', 'unittest', 'discover', '-s', 'tests', '-v') 'python-tests.log'
    $pythonLog = Get-Content (Join-Path $runDirectory 'python-tests.log') -Raw
    if ($pythonLog -notmatch 'Ran (\d+) tests' -or $pythonLog -notmatch '(?m)^OK\s*$') { throw 'Python tests did not produce a passing nonempty result.' }
    $pythonTests = [int]([regex]::Match($pythonLog, 'Ran (\d+) tests').Groups[1].Value)
    if ($pythonTests -le 0) { throw 'Python discovered no tests.' }
    $result.stages.python = @{ tests = $pythonTests; status = 'PASS' }
    Invoke-Logged $Python @('-m', 'pip_audit', '-r', 'tools/requirements.txt') 'pip-audit.log'
    $result.stages.python_dependency_audit = 'PASS'
    Invoke-Logged '.\gradlew.bat' @(':core:check', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug',
        ':app:drishtiApi36DebugAndroidTest', '--rerun-tasks',
        '-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect',
        "-PdrishtiTestAssets=$assetDirectory", '-Pandroid.testInstrumentationRunnerArguments.visionFixtureAsset=airport.jpg') 'gradle.log'
    $result.stages.kotlin = Read-JUnit @(Get-ChildItem 'core/build/test-results/test' -Filter 'TEST-*.xml')
    $androidReport = Get-Item 'app/build/outputs/androidTest-results/managedDevice/debug/drishtiApi36/TEST-drishtiApi36.xml'
    $result.stages.android = Read-JUnit @($androidReport)
    [xml]$androidXml = Get-Content -LiteralPath $androidReport.FullName -Raw
    foreach ($required in @('CameraPipelineInstrumentedTest','CloudSessionInterlockInstrumentedTest','DesignReviewInstrumentedTest','YoloxDetectorInstrumentedTest','ItemPhotoInstrumentedTest','AuditStorageInstrumentedTest','MainActivityJourneyInstrumentedTest')) {
        if ($androidXml.SelectNodes("//testcase[@classname='com.edgepulse.monitor.$required']").Count -eq 0) { throw "Required pipeline coverage missing: $required" }
    }
    $deviceResults = $androidReport.Directory.FullName
    $positive = Get-ChildItem $deviceResults -Filter 'logcat-*YoloxDetector*actualReusable*.txt' | Select-String 'COCO integration probe:.*airplane'
    $camera = Get-ChildItem $deviceResults -Filter 'logcat-*CameraPipeline*.txt' | Select-String 'CAMERA_PIPELINE.*uidTxDelta=0 uidRxDelta=0'
    if (!$positive -or !$camera) { throw 'Positive object detection or actual camera/UID-counter evidence is missing.' }
    $result.stages.positive_detection = 'PASS: pinned COCO airplane detected'
    $result.stages.camera_pipeline = 'PASS: frames, rescan, release and unchanged UID counters; not a field/network-capture acceptance claim'
    $coveragePath = 'core/build/reports/jacoco/test/jacocoTestReport.xml'
    [xml]$coverage = Get-Content $coveragePath -Raw
    $line = $coverage.SelectSingleNode('/report/counter[@type="LINE"]')
    if (!$line -or ([double]$line.covered + [double]$line.missed) -le 0) { throw 'Core line coverage evidence is missing or empty.' }
    $result.core_line_coverage = [double]$line.covered / ([double]$line.covered + [double]$line.missed)
    if ($result.core_line_coverage -lt .80) { throw 'Core line coverage is below 80%.' }
    $apk = Get-Item 'app/build/outputs/apk/debug/app-debug.apk'
    $result.apk_sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $androidReport.FullName -Destination (Join-Path $runDirectory 'android-results.xml')
    Copy-Item -LiteralPath $coveragePath -Destination (Join-Path $runDirectory 'core-coverage.xml')
    $renderDirectory = 'app/build/outputs/managed_device_android_test_additional_output'
    $renders = @(Get-ChildItem $renderDirectory -Recurse -Filter '*.png' -ErrorAction SilentlyContinue)
    if ($renders.Count -eq 0) { throw 'Native UI render artifacts were not collected.' }
    $renderDestination = Join-Path $runDirectory 'renders'
    New-Item -ItemType Directory -Path $renderDestination | Out-Null
    foreach ($render in $renders) { Copy-Item -LiteralPath $render.FullName -Destination (Join-Path $renderDestination $render.Name) }
    $result.render_files = $renders.Count
    if ((Source-Evidence).sha256 -ne $result.source_inputs_sha256) { throw 'Source inputs changed during the run; results must be rerun for the final source.' }
    $result.human_gates = 'NOT PASSED: real provider photo request, physical profiling/field accuracy/battery/OEM alarms and full network capture'
    $result.status = 'PASS_AUTOMATED'
} catch {
    $result.status = 'FAILED'
    $result.error = $_.Exception.Message
    throw
} finally {
    $env:JAVA_HOME = $previousJava
    $result.finished_utc = [DateTime]::UtcNow.ToString('o')
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'summary.json') -Encoding utf8
    Pop-Location
    Write-Host "Pipeline evidence: $runDirectory"
}
