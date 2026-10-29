[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [switch]$AllowEmulator
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (!(Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'ADB executable is missing. Pass -Adb.' }
if (!(Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw 'Build :app:assembleDebug first.' }
function Invoke-DeviceAdb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments[0])`n$($result -join "`n")" }
    return ($result -join "`n").Trim()
}
if ((Invoke-DeviceAdb @('get-state')) -ne 'device') { throw 'Device is not connected/authorized.' }
$emulator = (Invoke-DeviceAdb @('shell', 'getprop', 'ro.kernel.qemu')) -eq '1'
if ($emulator -and !$AllowEmulator) { throw 'Physical acceptance requires a phone. Use -AllowEmulator only for integration smoke tests.' }
$evidence = [ordered]@{
    observed_utc = [DateTime]::UtcNow.ToString('o')
    serial = $Serial
    emulator = $emulator
    apk_sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    fingerprint = Invoke-DeviceAdb @('shell', 'getprop', 'ro.build.fingerprint')
    manufacturer = Invoke-DeviceAdb @('shell', 'getprop', 'ro.product.manufacturer')
    model = Invoke-DeviceAdb @('shell', 'getprop', 'ro.product.model')
    sdk = Invoke-DeviceAdb @('shell', 'getprop', 'ro.build.version.sdk')
    soc = Invoke-DeviceAdb @('shell', 'getprop', 'ro.soc.model')
    install = Invoke-DeviceAdb @('install', '-r', $apkPath)
}
if ($evidence.install -notmatch '(?m)^Success$') { throw 'APK installation did not report Success.' }
$evidence.installed_package = Invoke-DeviceAdb @('shell', 'pm', 'path', 'com.edgepulse.monitor')
if ($evidence.installed_package -notmatch '^package:') { throw 'Installed package path was not found.' }
Invoke-DeviceAdb @('shell', 'am', 'force-stop', 'com.edgepulse.monitor') | Out-Null
$evidence.cold_launch = Invoke-DeviceAdb @('shell', 'am', 'start', '-W', '-n', 'com.edgepulse.monitor/.MainActivity')
if ($evidence.cold_launch -notmatch '(?m)^Status: ok') { throw 'Cold launch did not report Status: ok.' }
$evidence.physical_latency = 'HUMAN-GATED: run Run local benchmark on the phone, export its report and profile the actual accelerator.'
$evidence.field_accuracy_and_battery = 'HUMAN-GATED / NOT PASSED'
$destination = Join-Path $repoRoot ('artifacts\device-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '.json')
New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
$evidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $destination -Encoding utf8
Write-Output "Installed and cold-launched the native Android APK. Evidence: $destination"
Write-Output 'Use the app to run/export the benchmark. This smoke test does not pass any physical performance or field gate.'
