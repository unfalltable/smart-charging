param([switch]$DeleteData)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $PSScriptRoot 'compose.local.yaml'
. (Join-Path $PSScriptRoot 'configuration.ps1')
[void](Move-LegacyDeploymentConfiguration -Workspace $workspace)
$environmentFile = Get-DeploymentConfigurationPath -Workspace $workspace

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker was not found.'
}
if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw '.env was not found. Run config-manager.cmd init first.'
}
$configuration = (Initialize-DeploymentConfiguration -Workspace $workspace).Values
Set-DeploymentProcessConfiguration -Values $configuration

# Docker Compose resolves and validates published ports even for `down`. Legacy
# configuration files may contain malformed port values, so cleanup must not
# depend on those values. Process variables take precedence over --env-file and
# are scoped to this PowerShell process only.
$cleanupOverrides = [ordered]@{
    APP_BUILD_REVISION = 'cleanup-operation'
    ADMIN_WEB_PORT = '8088'
    CORE_PORT = '18080'
    DEVICE_GATEWAY_PORT = '9000'
    DEVICE_MANAGEMENT_PORT = '9001'
    DEVICE_GATEWAY_BIND_ADDRESS = '127.0.0.1'
    POSTGRES_PORT = '15432'
    VALKEY_PORT = '16379'
    NATS_PORT = '14222'
    NATS_MONITOR_PORT = '18222'
    HTTP_PORT = '80'
    HTTPS_PORT = '443'
    WECHAT_PAYMENT_DIRECTORY = [IO.Path]::GetFullPath((Join-Path $workspace 'runtime-secrets/wechat-pay'))
}
foreach ($entry in $cleanupOverrides.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
}

$arguments = @('compose', '--env-file', $environmentFile, '--file', $composeFile,
    '--profile', 'device', '--profile', 'production', 'down', '--remove-orphans')
if ($DeleteData) { $arguments += '--volumes' }

Push-Location $workspace
try {
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose shutdown failed.' }
    if ($DeleteData) {
        Write-Host 'Containers and local Docker data volumes were deleted.' -ForegroundColor Yellow
        Write-Host 'The root .env file was preserved.' -ForegroundColor Green
    }
    else {
        Write-Host 'Containers stopped. Database data remains in Docker volumes.' -ForegroundColor Green
    }
}
finally {
    Pop-Location
}
