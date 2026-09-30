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
[void](Set-DeploymentProcessConfiguration -Values $configuration)
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
$publicHost = [string]$configuration['PUBLIC_HOST']
if (-not [string]::IsNullOrWhiteSpace($publicHost)) {
    $compose += @('--profile', 'production')
}
Push-Location $workspace
try {
    $buildServices = @('core', 'admin-web', 'snapshot-tool')
    if ($deviceGatewayEnabled) { $buildServices += 'device-gateway' }

    & docker @compose --profile maintenance build @buildServices
    if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed.' }
    if ($BuildOnly) {
        Write-Host 'All Docker images were built successfully.' -ForegroundColor Green
        exit 0
    }

    $paymentFiles = @()
    if (Get-ConfigurationBoolean -Values $configuration -Name 'WECHAT_PAYMENT_ENABLED') {
        $paymentFiles += '/run/secrets/wechat-pay/merchant-private-key.pem'
        $paymentFiles += '/run/secrets/wechat-pay/wechat-pay-public-key.pem'
    }
    foreach ($prefix in Get-CustomMerchantConfigurationPrefixes -Values $configuration) {
        $paymentFiles += [string]$configuration["${prefix}_PRIVATE_KEY_PATH"]
        $paymentFiles += [string]$configuration["${prefix}_PUBLIC_KEY_PATH"]
    }
    $readableCheck = 'for material_path do test -r $material_path || exit 1; done'
    if ($paymentFiles.Count -gt 0) {
        & docker @compose run --rm --no-deps --entrypoint /bin/sh core -c $readableCheck material-check @paymentFiles
        if ($LASTEXITCODE -ne 0) {
            throw 'The core container UID 10001 cannot read its configured payment key material. Grant this UID read/traverse access through a controlled ACL; do not make private keys world-readable.'
        }
    }
    if ($deviceGatewayEnabled) {
        $deviceFiles = @('/run/secrets/device-tls/tls.crt', '/run/secrets/device-tls/tls.key', '/run/secrets/device-tls/ca.crt')
        & docker @compose run --rm --no-deps --entrypoint /bin/sh device-gateway -c $readableCheck material-check @deviceFiles
        if ($LASTEXITCODE -ne 0) {
            throw 'The device gateway UID 10001 cannot read its TLS material. Grant read/traverse access through a controlled ACL; startup will not change certificate ownership.'
        }
    }

    # Re-run the idempotent role bootstrap even when the old task exited cleanly.
    # Only this stateless container is replaced; named data volumes are retained.
    & docker @compose rm --force --stop database-bootstrap
    if ($LASTEXITCODE -ne 0) { throw 'Could not refresh the database role bootstrap task.' }

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
            $gatewayReady = $true
            if ($deviceGatewayEnabled) {
                $gatewayPort = [string]$configuration['DEVICE_MANAGEMENT_PORT']
                $gateway = Invoke-RestMethod -Uri "http://127.0.0.1:$gatewayPort/actuator/health/readiness" -TimeoutSec 3
                $gatewayReady = $gateway.status -eq 'UP'
            }
            $ready = $core.status -eq 'UP' -and $web.StatusCode -eq 200 -and $homepageReady -and `
                $revisionReady -and $webRevisionReady -and $gatewayReady
            $lastReadinessStatus = "core=$($core.status), healthz=$($web.StatusCode), " +
                "homepage=$homepageReady, revision=$([string]$build.build.revision), " +
                "webRevision=$webRevision, gatewayReady=$gatewayReady, expectedRevision=$buildRevision"
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
        if ($deviceGatewayEnabled) { $logServices += 'device-gateway' }
        & docker @compose logs --tail 80 @logServices
        Write-Warning "Last readiness check: $lastReadinessStatus"
        throw "Services did not become ready within $TimeoutSeconds seconds. Review the container logs above."
    }

    if (-not [string]::IsNullOrWhiteSpace($publicHost)) {
        $publicReady = $false
        $publicError = ''
        do {
            try {
                $publicHealth = Invoke-WebRequest -Uri "https://$publicHost/healthz" -TimeoutSec 10 -UseBasicParsing
                $publicReady = $publicHealth.StatusCode -eq 200
            }
            catch { $publicError = $_.Exception.Message }
            if (-not $publicReady) { Start-Sleep -Seconds 2 }
        } while (-not $publicReady -and [DateTime]::UtcNow -lt $deadline)
        if (-not $publicReady) {
            & docker @compose logs --no-color --tail 80 public-edge
            throw "Local services are ready but the public HTTPS entrypoint is not reachable. Check PUBLIC_HOST DNS, inbound ports 80/443 and Caddy certificate logs. $publicError"
        }
        Write-Host "Public console and mini-program API: https://$publicHost" -ForegroundColor Green
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
