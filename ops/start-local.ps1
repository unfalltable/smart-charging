param(
    [switch]$BuildOnly,
    [switch]$EnableDeviceGateway,
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $PSScriptRoot 'compose.local.yaml'
. (Join-Path $PSScriptRoot 'configuration.ps1')
. (Join-Path $PSScriptRoot 'http-response.ps1')

$configurationState = Initialize-DeploymentConfiguration -Workspace $workspace
$environmentFile = $configurationState.Path
$configuration = $configurationState.Values
if ($EnableDeviceGateway) {
    $configuration['DEVICE_GATEWAY_ENABLED'] = 'true'
}
$configurationErrors = @(Test-DeploymentConfiguration -Workspace $workspace -Values $configuration)
if ($configurationErrors.Count -gt 0) {
    throw "Deployment configuration is invalid. Edit .env, then run config-manager.cmd validate.`n - $($configurationErrors -join "`n - ")"
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
$buildRevision = [string](& git -C $workspace rev-parse --verify HEAD)
if ($LASTEXITCODE -ne 0 -or $buildRevision -notmatch '^[0-9a-f]{40}$') {
    throw 'The current Git revision could not be determined.'
}
$buildRevision = $buildRevision.Trim()
[Environment]::SetEnvironmentVariable('APP_BUILD_REVISION', $buildRevision, 'Process')

$compose = @('compose', '--env-file', $environmentFile, '--file', $composeFile)
$deviceGatewayEnabled = Get-ConfigurationBoolean -Values $configuration -Name 'DEVICE_GATEWAY_ENABLED'
if ($deviceGatewayEnabled) {
    $compose += @('--profile', 'device')
}
Push-Location $workspace
try {
    $buildServices = @('core', 'admin-web')
    if ($deviceGatewayEnabled) { $buildServices += 'device-gateway' }

    & docker @compose build @buildServices
    if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed.' }
    if ($BuildOnly) {
        Write-Host 'All Docker images were built successfully.' -ForegroundColor Green
        exit 0
    }

    & docker @compose up --detach --no-build --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Warning 'Docker Compose could not satisfy a service dependency. Container status and core diagnostics follow.'
        & docker @compose ps --all
        & docker @compose logs --no-color --tail 160 core
        throw 'Docker Compose startup failed. Review the first ERROR or Caused by entry in the diagnostics above.'
    }

    $adminPort = [string]$configuration['ADMIN_WEB_PORT']
    $corePort = [string]$configuration['CORE_PORT']
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $ready = $false
    $lastReadinessStatus = 'Readiness checks have not completed yet.'
    do {
        try {
            $core = Invoke-RestMethod -Uri "http://127.0.0.1:$corePort/actuator/health/readiness" -TimeoutSec 3
            $build = Invoke-RestMethod -Uri "http://127.0.0.1:$corePort/actuator/info" -TimeoutSec 3
            $web = Invoke-WebRequest -Uri "http://127.0.0.1:$adminPort/healthz" -TimeoutSec 3 -UseBasicParsing
            $webBuild = Invoke-WebRequest -Uri "http://127.0.0.1:$adminPort/build-revision" -TimeoutSec 3 -UseBasicParsing
            $homepageResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$adminPort/" -TimeoutSec 3 -UseBasicParsing
            $homepageReady = $homepageResponse.StatusCode -eq 200 -and `
                $homepageResponse.Content -match '<div id="app"></div>'
            $revisionReady = [string]$build.build.revision -eq $buildRevision
            $webRevision = (Convert-HttpContentToText -Content $webBuild.Content).Trim()
            $webRevisionReady = $webRevision -eq $buildRevision
            $ready = $core.status -eq 'UP' -and $web.StatusCode -eq 200 -and $homepageReady -and `
                $revisionReady -and $webRevisionReady
            $lastReadinessStatus = "core=$($core.status), healthz=$($web.StatusCode), " +
                "homepage=$homepageReady, revision=$([string]$build.build.revision), " +
                "webRevision=$webRevision, expectedRevision=$buildRevision"
        }
        catch {
            $ready = $false
            $lastReadinessStatus = "error=$($_.Exception.Message)"
        }
        if (-not $ready) { Start-Sleep -Seconds 2 }
    } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)

    if (-not $ready) {
        & docker @compose ps --all
        $logServices = @('core', 'admin-web')
        & docker @compose logs --tail 80 @logServices
        Write-Warning "Last readiness check: $lastReadinessStatus"
        throw "Services did not become ready within $TimeoutSeconds seconds. Review the container logs above."
    }

    Write-Host ''
    Write-Host 'The production-mode charging platform is ready without seeding business data.' -ForegroundColor Green
    Write-Host "Admin console: http://127.0.0.1:$adminPort/"
    Write-Host "API through local proxy: http://127.0.0.1:$adminPort/api/v1"
    Write-Host "Core API (diagnostics): http://127.0.0.1:$corePort"
    Write-Host 'Initial login: run .\config-manager.cmd credentials' -ForegroundColor Yellow
    Write-Host 'Log in as the single platform super-administrator, change the one-time password, then create tenants and assign their accounts.' -ForegroundColor Yellow
    if ($deviceGatewayEnabled) {
        Write-Host 'Device gateway: enabled with the supplied TLS certificates.'
    }
    else {
        Write-Host 'Device gateway: disabled until real hardware TLS material is supplied.' -ForegroundColor Yellow
    }
    Write-Host 'Startup did not create any tenant, station, device, tariff, order or payment data.'
    Write-Host ''
    & docker @compose ps --all
}
finally {
    Pop-Location
}
