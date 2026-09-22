$ErrorActionPreference = 'Stop'

$workspace = Split-Path -Parent $PSScriptRoot
$seedFile = Join-Path $PSScriptRoot 'pilot-12-port.sql'

if (-not (Test-Path -LiteralPath $seedFile -PathType Leaf)) {
    throw "Pilot seed file does not exist: $seedFile"
}

Push-Location $workspace
try {
    Get-Content -LiteralPath $seedFile -Raw |
        docker compose --env-file .env -f ops/compose.local.yaml exec -T postgres `
            psql --set=ON_ERROR_STOP=1 -U charging_app -d charging
    if ($LASTEXITCODE -ne 0) {
        throw "Pilot database initialization failed"
    }
}
finally {
    Pop-Location
}
