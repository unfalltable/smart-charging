param(
    [switch]$BuildOnly,
    [switch]$EnableDeviceGateway,
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $PSScriptRoot 'compose.local.yaml'
$environmentFile = Join-Path $workspace '.env.docker'

function New-RandomBytes([int]$length) {
    $bytes = New-Object byte[] $length
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) }
    finally { $generator.Dispose() }
    return ,$bytes
}

function New-HexSecret([int]$length = 32) {
    return -join ((New-RandomBytes $length) | ForEach-Object { $_.ToString('x2') })
}

function New-Base64Secret([int]$length = 32) {
    return [Convert]::ToBase64String((New-RandomBytes $length))
}

function Get-EnvironmentValue([string]$name) {
    $entry = Get-Content -LiteralPath $environmentFile | Where-Object { $_ -match "^$([regex]::Escape($name))=" } |
        Select-Object -Last 1
    if ($null -eq $entry) { return '' }
    return ($entry -split '=', 2)[1].Trim()
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker was not found. Install and start Docker Desktop, then run docker-start.cmd again.'
}

docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'The Docker service is not running. Start Docker Desktop first.'
}

if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    $lines = @(
        "POSTGRES_PASSWORD=$(New-HexSecret)"
        "VALKEY_PASSWORD=$(New-HexSecret)"
        "QR_SIGNING_SECRET=$(New-HexSecret)"
        "DEVICE_CREDENTIAL_MASTER_KEY_BASE64=$(New-Base64Secret)"
        "AUTH_JWT_SECRET_BASE64=$(New-Base64Secret)"
        'APP_JWT_ISSUER=http://127.0.0.1:8088'
        'OIDC_ISSUER_URI='
        'API_JWT_AUDIENCE=smart-charging-api'
        'ALLOWED_ORIGINS=http://127.0.0.1:8088'
        'VITE_OIDC_AUTHORIZATION_ENDPOINT='
        'VITE_OIDC_TOKEN_ENDPOINT='
        'VITE_OIDC_CLIENT_ID='
        'VITE_OIDC_REDIRECT_URI=http://127.0.0.1:8088/'
        'WECHAT_IDENTITY_ENABLED=false'
        'WECHAT_NOTIFICATION_ENABLED=false'
        'ADMIN_WEB_PORT=8088'
        'CORE_PORT=18080'
        'DEVICE_GATEWAY_PORT=9000'
        'DEVICE_MANAGEMENT_PORT=9001'
        'DEVICE_GATEWAY_BIND_ADDRESS=127.0.0.1'
        'DEVICE_TLS_DIRECTORY='
        'POSTGRES_PORT=15432'
        'VALKEY_PORT=16379'
        'NATS_PORT=14222'
        'NATS_MONITOR_PORT=18222'
    )
    [IO.File]::WriteAllLines($environmentFile, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host 'Generated real random secrets in .env.docker (Git ignored).' -ForegroundColor Green
    Write-Host 'Add the real OIDC deployment values to .env.docker, then run this command again.' -ForegroundColor Yellow
}

$environmentLines = @(Get-Content -LiteralPath $environmentFile)
if ($environmentLines | Where-Object { $_ -match '^PILOT_DEVICE_SECRET=' }) {
    throw 'Legacy demo data may still exist. Run docker-stop.cmd -DeleteData once, then run docker-start.cmd again.'
}
$missingDefaults = [ordered]@{
    APP_JWT_ISSUER = 'http://127.0.0.1:8088'
    OIDC_ISSUER_URI = ''
    API_JWT_AUDIENCE = 'smart-charging-api'
    ALLOWED_ORIGINS = 'http://127.0.0.1:8088'
    VITE_OIDC_AUTHORIZATION_ENDPOINT = ''
    VITE_OIDC_TOKEN_ENDPOINT = ''
    VITE_OIDC_CLIENT_ID = ''
    VITE_OIDC_REDIRECT_URI = 'http://127.0.0.1:8088/'
    WECHAT_IDENTITY_ENABLED = 'false'
    WECHAT_NOTIFICATION_ENABLED = 'false'
    DEVICE_GATEWAY_BIND_ADDRESS = '127.0.0.1'
    DEVICE_TLS_DIRECTORY = ''
}
foreach ($entry in $missingDefaults.GetEnumerator()) {
    if (-not ($environmentLines | Where-Object { $_ -match "^$([regex]::Escape($entry.Key))=" })) {
        $environmentLines += "$($entry.Key)=$($entry.Value)"
    }
}
[IO.File]::WriteAllLines($environmentFile, $environmentLines, [Text.UTF8Encoding]::new($false))

$compose = @('compose', '--env-file', $environmentFile, '--file', $composeFile)
$requiredConfiguration = @(
    'APP_JWT_ISSUER',
    'OIDC_ISSUER_URI',
    'API_JWT_AUDIENCE',
    'ALLOWED_ORIGINS',
    'VITE_OIDC_AUTHORIZATION_ENDPOINT',
    'VITE_OIDC_TOKEN_ENDPOINT',
    'VITE_OIDC_CLIENT_ID',
    'VITE_OIDC_REDIRECT_URI'
)
$missingConfiguration = @($requiredConfiguration | Where-Object { [string]::IsNullOrWhiteSpace((Get-EnvironmentValue $_)) })
if ($missingConfiguration.Count -gt 0) {
    throw "Production authentication configuration is incomplete in .env.docker: $($missingConfiguration -join ', ')"
}
if ($EnableDeviceGateway) {
    $tlsDirectory = Get-EnvironmentValue 'DEVICE_TLS_DIRECTORY'
    if ([string]::IsNullOrWhiteSpace($tlsDirectory)) {
        throw 'DEVICE_TLS_DIRECTORY is required when -EnableDeviceGateway is used.'
    }
    $resolvedTlsDirectory = if ([IO.Path]::IsPathRooted($tlsDirectory)) {
        [IO.Path]::GetFullPath($tlsDirectory)
    }
    else {
        [IO.Path]::GetFullPath((Join-Path $workspace $tlsDirectory))
    }
    foreach ($fileName in @('tls.crt', 'tls.key', 'ca.crt')) {
        if (-not (Test-Path -LiteralPath (Join-Path $resolvedTlsDirectory $fileName) -PathType Leaf)) {
            throw "Real device TLS file is missing: $fileName"
        }
    }
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

    $adminPort = ((Get-Content -LiteralPath $environmentFile | Where-Object { $_ -match '^ADMIN_WEB_PORT=' }) -split '=', 2)[1]
    $corePort = ((Get-Content -LiteralPath $environmentFile | Where-Object { $_ -match '^CORE_PORT=' }) -split '=', 2)[1]
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
    if ($EnableDeviceGateway) {
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
