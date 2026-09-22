param(
    [switch]$BuildOnly,
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
        "PILOT_DEVICE_SECRET=$(New-HexSecret)"
        "QR_SIGNING_SECRET=$(New-HexSecret)"
        "DEVICE_CREDENTIAL_MASTER_KEY_BASE64=$(New-Base64Secret)"
        "AUTH_JWT_SECRET_BASE64=$(New-Base64Secret)"
        'ADMIN_WEB_PORT=8088'
        'CORE_PORT=8080'
        'DEVICE_GATEWAY_PORT=9000'
        'DEVICE_MANAGEMENT_PORT=9001'
        'POSTGRES_PORT=5432'
        'VALKEY_PORT=6379'
        'NATS_PORT=4222'
        'NATS_MONITOR_PORT=8222'
    )
    [IO.File]::WriteAllLines($environmentFile, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host 'Generated local random secrets in .env.docker (Git ignored).' -ForegroundColor Green
}

$compose = @('compose', '--env-file', $environmentFile, '--file', $composeFile)
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
    do {
        try {
            $core = Invoke-RestMethod -Uri "http://127.0.0.1:$corePort/actuator/health/readiness" -TimeoutSec 3
            $web = Invoke-WebRequest -Uri "http://127.0.0.1:$adminPort/healthz" -TimeoutSec 3 -UseBasicParsing
            $deviceHeaders = @{ 'X-Tenant-Id' = '11111111-1111-1111-1111-111111111111' }
            $devices = Invoke-RestMethod -Uri "http://127.0.0.1:$corePort/api/v1/admin/assets/devices" `
                -Headers $deviceHeaders -TimeoutSec 3
            $simulatorOnline = $null -ne ($devices | Where-Object {
                $_.deviceCode -eq 'PILE001' -and $_.status -eq 'ONLINE'
            } | Select-Object -First 1)
            $ready = $core.status -eq 'UP' -and $web.StatusCode -eq 200 -and $simulatorOnline
        }
        catch {
            Start-Sleep -Seconds 2
        }
    } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)

    if (-not $ready) {
        & docker @compose ps --all
        & docker @compose logs --tail 80 core device-gateway bootstrap admin-web simulator
        throw "Services did not become ready within $TimeoutSeconds seconds. Review the container logs above."
    }

    $qrSecret = ((Get-Content -LiteralPath $environmentFile | Where-Object { $_ -match '^QR_SIGNING_SECRET=' }) -split '=', 2)[1]
    $env:QR_SIGNING_SECRET = $qrSecret
    $qrContent = & (Join-Path $PSScriptRoot 'generate-pilot-qr.ps1')

    Write-Host ''
    Write-Host 'The local charging platform is ready.' -ForegroundColor Green
    Write-Host "Admin console: http://localhost:$adminPort"
    Write-Host "Core API: http://localhost:$corePort"
    Write-Host 'Simulator: PILE001 (12 connectors, connected automatically)'
    Write-Host "Connector 1 QR content: $qrContent"
    Write-Host ''
    & docker @compose ps --all
}
finally {
    Pop-Location
}
