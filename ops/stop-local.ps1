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

# Docker Compose resolves and validates published ports even for `down`. Legacy
# configuration files may contain malformed port values, so cleanup must not
# depend on those values. Process variables take precedence over --env-file and
# are scoped to this PowerShell process only.
$cleanupOverrides = [ordered]@{
    ADMIN_WEB_PORT = '8088'
    CORE_PORT = '18080'
    KEYCLOAK_PORT = '19090'
    DEVICE_GATEWAY_PORT = '9000'
    DEVICE_MANAGEMENT_PORT = '9001'
    DEVICE_GATEWAY_BIND_ADDRESS = '127.0.0.1'
    POSTGRES_PORT = '15432'
    VALKEY_PORT = '16379'
    NATS_PORT = '14222'
    NATS_MONITOR_PORT = '18222'
    WECHAT_PAYMENT_DIRECTORY = [IO.Path]::GetFullPath((Join-Path $workspace 'runtime-secrets/wechat-pay'))
    KEYCLOAK_IMPORT_DIRECTORY = [IO.Path]::GetFullPath((Join-Path $workspace 'runtime-secrets/keycloak'))
}
foreach ($entry in $cleanupOverrides.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
}

$arguments = @('compose', '--env-file', $environmentFile, '--file', $composeFile,
    '--profile', 'bundled-identity', '--profile', 'device', 'down', '--remove-orphans')
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
