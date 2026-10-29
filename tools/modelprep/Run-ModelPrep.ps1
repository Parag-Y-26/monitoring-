param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$jobDirectory = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$dataDirectory = Join-Path $repoRoot 'artifacts\modelprep'
$lock = Get-Content -LiteralPath (Join-Path $jobDirectory 'source-lock.json') -Raw | ConvertFrom-Json

& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker is not ready. Start Docker Desktop manually; this job does not repair or alter WSL installations.' }
& $Python (Join-Path $jobDirectory 'prepare_sources.py') --output $dataDirectory
if ($LASTEXITCODE -ne 0) { throw 'Source preparation failed; conversion was not started.' }
& docker build --platform linux/amd64 --build-arg "PYTHON_IMAGE=$($lock.python_image)" --tag edgepulse-coco-modelprep $jobDirectory
if ($LASTEXITCODE -ne 0) { throw 'Converter image build failed.' }
& docker run --rm --platform linux/amd64 --network none --cpus 2 --memory 4g --pids-limit 256 --mount "type=bind,source=$dataDirectory,target=/work" edgepulse-coco-modelprep
if ($LASTEXITCODE -ne 0) { throw 'Conversion failed; do not import or claim the bundle as complete.' }
Write-Output "Review $dataDirectory\bundle\provenance.json, then import with tools/import_benchmark_models.py. Physical benchmarking remains HUMAN-GATED."
