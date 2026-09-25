param([switch]$DeleteData)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $PSScriptRoot 'compose.local.yaml'
$environmentFile = Join-Path $workspace '.env.docker'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker was not found.'
}
if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw '.env.docker was not found. Run docker-start.cmd first.'
}

$arguments = @('compose', '--env-file', $environmentFile, '--file', $composeFile, 'down', '--remove-orphans')
if ($DeleteData) { $arguments += '--volumes' }

Push-Location $workspace
try {
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose shutdown failed.' }
    if ($DeleteData) {
        Remove-Item -LiteralPath $environmentFile -Force
        Write-Host 'Containers and local Docker data volumes were deleted.' -ForegroundColor Yellow
        Write-Host 'The local secret file was also removed; the next startup will generate new secrets.' -ForegroundColor Yellow
    }
    else {
        Write-Host 'Containers stopped. Database data remains in Docker volumes.' -ForegroundColor Green
    }
}
finally {
    Pop-Location
}
