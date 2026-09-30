param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'configuration.ps1')
$state = Initialize-DeploymentConfiguration -Workspace $workspace
$values = $state.Values
Set-DeploymentProcessConfiguration -Values $values
[Environment]::SetEnvironmentVariable('APP_BUILD_REVISION', 'backup-operation', 'Process')
$compose = @('compose', '--env-file', $state.Path, '--file', (Join-Path $PSScriptRoot 'compose.local.yaml'),
    '--profile', 'device', '--profile', 'production')
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker was not found.' }
$snapshotImage = 'smart-charging-local-snapshot-tool:latest'
$availableImage = @(& docker image ls $snapshotImage --format '{{.ID}}')
if ($LASTEXITCODE -ne 0) { throw 'Docker is not ready.' }
if ($availableImage.Count -eq 0) {
    & docker build --tag $snapshotImage --file (Join-Path $PSScriptRoot 'snapshot.Dockerfile') $PSScriptRoot
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the snapshot tool. Existing services are still running.' }
}

$backupRoot = Resolve-ConfigurationDirectory -Workspace $workspace -ConfiguredPath ([string]$values['BACKUP_DIRECTORY'])
$backupPath = Join-Path $backupRoot ("snapshot-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))-$([guid]::NewGuid().ToString('N').Substring(0, 8))")
foreach ($configurationName in @('WECHAT_PAYMENT_DIRECTORY', 'DEVICE_TLS_DIRECTORY')) {
    $secretDirectory = Resolve-ConfigurationDirectory -Workspace $workspace -ConfiguredPath ([string]$values[$configurationName])
    if (-not [string]::IsNullOrWhiteSpace($secretDirectory)) {
        $secretPrefix = $secretDirectory.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if ($backupPath.StartsWith($secretPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'BACKUP_DIRECTORY must not be inside a payment or device key-material directory.'
        }
    }
}
[void](New-Item -ItemType Directory -Path $backupPath -Force)
Protect-GeneratedConfigurationPath -Path $backupRoot -Mode '700'
Protect-GeneratedConfigurationPath -Path $backupPath -Mode '700'
$availableVolumes = @(& docker volume ls --filter 'label=com.docker.compose.project=smart-charging-local' --format '{{.Name}}')
if ($LASTEXITCODE -ne 0) { throw 'Cannot list deployment data volumes.' }
$volumeNames = @('postgres-data', 'valkey-data', 'nats-data', 'caddy-data', 'caddy-config')
$volumes = @($volumeNames | Where-Object { $availableVolumes -contains "smart-charging-local_$_" })
foreach ($required in @('postgres-data', 'valkey-data', 'nats-data')) {
    if ($volumes -notcontains $required) { throw "Deployment volume is missing: smart-charging-local_$required" }
}
$runningServices = @(& docker @compose ps --status running --services)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read running deployment services.' }

try {
    Write-Host 'Stopping services briefly to make one consistent PostgreSQL / Valkey / NATS snapshot.' -ForegroundColor Yellow
    & docker @compose stop --timeout 60
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop every writer; no snapshot was taken.' }
    $remainingServices = @(& docker @compose ps --status running --services)
    if ($LASTEXITCODE -ne 0 -or $remainingServices.Count -gt 0) { throw 'A deployment service is still running; refusing an inconsistent snapshot.' }

    $archives = @()
    foreach ($name in $volumes) {
        $volume = "smart-charging-local_$name"
        $archiveName = "$name.tar.gz"
        & docker run --rm --pull never --network none --read-only `
            --mount "type=volume,source=$volume,target=/snapshot,readonly" `
            --mount "type=bind,source=$backupPath,target=/backup" `
            --entrypoint tar $snapshotImage --acls --xattrs --numeric-owner -czf "/backup/$archiveName" -C /snapshot .
        if ($LASTEXITCODE -ne 0) { throw "Could not archive $name. This snapshot is incomplete and must not be restored." }
        $archivePath = Join-Path $backupPath $archiveName
        $archives += [ordered]@{ name = $name; file = $archiveName; sha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash }
    }
    Copy-Item -LiteralPath $state.Path -Destination (Join-Path $backupPath 'deployment.env')
    $keyMaterial = @()
    foreach ($entry in @(@('WECHAT_PAYMENT_DIRECTORY', 'wechat-pay'), @('DEVICE_TLS_DIRECTORY', 'device-tls'))) {
        $directory = Resolve-ConfigurationDirectory -Workspace $workspace -ConfiguredPath ([string]$values[$entry[0]])
        if (-not [string]::IsNullOrWhiteSpace($directory) -and (Test-Path -LiteralPath $directory -PathType Container)) {
            $materialArchive = "key-material-$($entry[1]).tar.gz"
            & docker run --rm --pull never --network none --read-only `
                --mount "type=bind,source=$directory,target=/material,readonly" `
                --mount "type=bind,source=$backupPath,target=/backup" `
                --entrypoint tar $snapshotImage --acls --xattrs --numeric-owner -czf "/backup/$materialArchive" -C /material .
            if ($LASTEXITCODE -ne 0) { throw "Could not snapshot $($entry[1]) key material." }
            $keyMaterial += [ordered]@{
                configuration = $entry[0]; directory = $entry[1]; file = $materialArchive
                sha256 = (Get-FileHash -LiteralPath (Join-Path $backupPath $materialArchive) -Algorithm SHA256).Hash
            }
        }
    }
    $manifest = [ordered]@{
        format = 1
        project = 'smart-charging-local'
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        postgresMajor = 18
        archives = $archives
        environmentSha256 = (Get-FileHash -LiteralPath (Join-Path $backupPath 'deployment.env') -Algorithm SHA256).Hash
        keyMaterial = $keyMaterial
    } | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText((Join-Path $backupPath 'manifest.json'), $manifest, (New-Object Text.UTF8Encoding($false)))
    Protect-GeneratedConfigurationPath -Path (Join-Path $backupPath 'deployment.env')
    Write-Host "Consistent private snapshot saved: $backupPath" -ForegroundColor Green
    Write-Host 'The snapshot contains account data and secrets. Store an encrypted off-machine copy; do not upload it to Git.' -ForegroundColor Yellow
}
finally {
    if ($runningServices.Count -gt 0) {
        & docker @compose start @runningServices
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Some services did not restart. Run docker-start.cmd and check health before accepting traffic.' }
    }
}
