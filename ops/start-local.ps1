param(
    [switch]$BuildOnly,
    [switch]$EnableDeviceGateway,
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $PSScriptRoot 'compose.local.yaml'
. (Join-Path $PSScriptRoot 'configuration.ps1')

$configurationState = Initialize-DeploymentConfiguration -Workspace $workspace
$environmentFile = $configurationState.Path
$configuration = $configurationState.Values
if ($EnableDeviceGateway) {
    $configuration['DEVICE_GATEWAY_ENABLED'] = 'true'
}
$configurationErrors = @(Test-DeploymentConfiguration -Workspace $workspace -Values $configuration)
if ($configurationErrors.Count -gt 0) {
    throw "Deployment configuration is invalid. Run config-manager.cmd wizard, then config-manager.cmd validate.`n - $($configurationErrors -join "`n - ")"
}
[void](Export-MiniappDeploymentConfiguration -Workspace $workspace -Values $configuration)

$paymentDirectory = Resolve-ConfigurationDirectory -Workspace $workspace -ConfiguredPath ([string]$configuration['WECHAT_PAYMENT_DIRECTORY'])
if (-not (Test-Path -LiteralPath $paymentDirectory -PathType Container)) {
    [void](New-Item -ItemType Directory -Path $paymentDirectory -Force)
}
[Environment]::SetEnvironmentVariable('WECHAT_PAYMENT_DIRECTORY', $paymentDirectory, 'Process')
if (-not [string]::IsNullOrWhiteSpace([string]$configuration['DEVICE_TLS_DIRECTORY'])) {
    $tlsDirectory = Resolve-ConfigurationDirectory -Workspace $workspace -ConfiguredPath ([string]$configuration['DEVICE_TLS_DIRECTORY'])
    [Environment]::SetEnvironmentVariable('DEVICE_TLS_DIRECTORY', $tlsDirectory, 'Process')
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker was not found. Install and start Docker Desktop, then run docker-start.cmd again.'
}

docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'The Docker service is not running. Start Docker Desktop first.'
}

$compose = @('compose', '--env-file', $environmentFile, '--file', $composeFile)
$deviceGatewayEnabled = Get-ConfigurationBoolean -Values $configuration -Name 'DEVICE_GATEWAY_ENABLED'
if ($deviceGatewayEnabled) {
    $compose += @('--profile', 'device')
}
Push-Location $workspace
try {
    if ($BuildOnly) {
        & docker @compose build
        if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed.' }
        Write-Host 'All Docker images were built successfully.' -ForegroundColor Green
        exit 0
    }

    & docker @compose up --detach --build --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed.' }

    $adminPort = [string]$configuration['ADMIN_WEB_PORT']
    $corePort = [string]$configuration['CORE_PORT']
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $ready = $false
    $lastReadinessStatus = 'Readiness checks have not completed yet.'
    do {
        try {
            $core = Invoke-RestMethod -Uri "http://127.0.0.1:$corePort/actuator/health/readiness" -TimeoutSec 3
            $web = Invoke-WebRequest -Uri "http://127.0.0.1:$adminPort/healthz" -TimeoutSec 3 -UseBasicParsing
            $homepageResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$adminPort/" -TimeoutSec 3 -UseBasicParsing
            $homepageReady = $homepageResponse.StatusCode -eq 200 -and `
                $homepageResponse.Content -match '<div id="app"></div>'
            $ready = $core.status -eq 'UP' -and $web.StatusCode -eq 200 -and $homepageReady
            $lastReadinessStatus = "core=$($core.status), healthz=$($web.StatusCode), " +
                "homepage=$homepageReady"
        }
        catch {
            $ready = $false
            $lastReadinessStatus = "error=$($_.Exception.Message)"
        }
        if (-not $ready) { Start-Sleep -Seconds 2 }
    } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)

    if (-not $ready) {
        & docker @compose ps --all
        & docker @compose logs --tail 80 core admin-web
        Write-Warning "Last readiness check: $lastReadinessStatus"
        throw "Services did not become ready within $TimeoutSeconds seconds. Review the container logs above."
    }

    Write-Host ''
    Write-Host 'The production-mode charging platform is ready with an empty business database.' -ForegroundColor Green
    Write-Host "Admin console: http://127.0.0.1:$adminPort/"
    Write-Host "API through local proxy: http://127.0.0.1:$adminPort/api/v1"
    Write-Host "Core API (diagnostics): http://127.0.0.1:$corePort"
    if ($deviceGatewayEnabled) {
        Write-Host 'Device gateway: enabled with the supplied TLS certificates.'
    }
    else {
        Write-Host 'Device gateway: disabled until real hardware TLS material is supplied.' -ForegroundColor Yellow
    }
    Write-Host 'No tenant, user, station, device, tariff, order or payment data was created.'
    Write-Host ''
    & docker @compose ps --all
}
finally {
    Pop-Location
}
