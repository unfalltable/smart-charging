param(
    [Parameter(Mandatory = $true)][string]$BackupPath,
    [switch]$ConfirmRestore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'configuration.ps1')
if (-not $ConfirmRestore) {
    throw 'Restore replaces this deployment data and .env. Take a current backup first, then repeat with -ConfirmRestore.'
}
$backupDirectory = [IO.Path]::GetFullPath($BackupPath)
$manifestPath = Join-Path $backupDirectory 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'A completed snapshot manifest was not found.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.format -ne 1 -or $manifest.project -ne 'smart-charging-local' -or $manifest.postgresMajor -ne 18) {
    throw 'This is not a supported PostgreSQL 18 deployment snapshot.'
}
$permittedNames = @('postgres-data', 'valkey-data', 'nats-data', 'caddy-data', 'caddy-config')
$seen = @{}
foreach ($archive in $manifest.archives) {
    $name = [string]$archive.name
    if ($permittedNames -notcontains $name -or $seen.ContainsKey($name) -or $archive.file -ne "$name.tar.gz") {
        throw 'Snapshot manifest contains an unexpected or duplicate data volume.'
    }
    $seen[$name] = $true
    $file = Join-Path $backupDirectory ([string]$archive.file)
    if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $archive.sha256) {
        throw "Snapshot archive is missing or has a bad checksum: $name"
    }
}
foreach ($required in @('postgres-data', 'valkey-data', 'nats-data')) {
    if (-not $seen.ContainsKey($required)) { throw "Snapshot is missing $required." }
}
$backupEnvironment = Join-Path $backupDirectory 'deployment.env'
if (-not (Test-Path -LiteralPath $backupEnvironment -PathType Leaf) -or
        (Get-FileHash -LiteralPath $backupEnvironment -Algorithm SHA256).Hash -ne $manifest.environmentSha256) {
    throw 'The snapshot environment file is missing or its checksum is invalid.'
}
$restoredValues = Read-DeploymentConfiguration -Path $backupEnvironment
$materialTargets = @{}
foreach ($entry in $manifest.keyMaterial) {
    $materialConfigurations = @{ 'wechat-pay' = 'WECHAT_PAYMENT_DIRECTORY'; 'device-tls' = 'DEVICE_TLS_DIRECTORY' }
    if (-not $materialConfigurations.ContainsKey([string]$entry.directory) -or
            $materialConfigurations[[string]$entry.directory] -ne [string]$entry.configuration -or
            $materialTargets.ContainsKey([string]$entry.directory)) {
        throw 'Snapshot key-material metadata is invalid.'
    }
    if ($entry.file -ne "key-material-$([string]$entry.directory).tar.gz") { throw 'Snapshot key-material archive name is invalid.' }
    $materialFile = Join-Path $backupDirectory ([string]$entry.file)
    if (-not (Test-Path -LiteralPath $materialFile -PathType Leaf) -or
            (Get-FileHash -LiteralPath $materialFile -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw 'Snapshot key material is missing or its checksum is invalid.'
    }
    $targetDirectory = Resolve-ConfigurationDirectory -Workspace $workspace -ConfiguredPath ([string]$restoredValues[[string]$entry.configuration])
    if ([string]::IsNullOrWhiteSpace($targetDirectory)) { throw 'Restored key-material destination is not configured.' }
    if ($targetDirectory -eq [IO.Path]::GetPathRoot($targetDirectory) -or $targetDirectory -eq [IO.Path]::GetFullPath($workspace)) {
        throw 'Refusing to restore key material into a filesystem or workspace root.'
    }
    $targetPrefix = $targetDirectory.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($backupDirectory.StartsWith($targetPrefix, [StringComparison]::OrdinalIgnoreCase) -or $targetDirectory -eq $backupDirectory) {
        throw 'Refusing to restore key material over the source snapshot.'
    }
    $materialTargets[[string]$entry.directory] = $targetDirectory
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker was not found.' }
$snapshotImage = 'smart-charging-local-snapshot-tool:latest'
$restoreImage = @(& docker image ls $snapshotImage --format '{{.ID}}')
if ($LASTEXITCODE -ne 0) { throw 'Docker is not ready.' }
if ($restoreImage.Count -eq 0) {
    & docker build --tag $snapshotImage --file (Join-Path $PSScriptRoot 'snapshot.Dockerfile') $PSScriptRoot
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the snapshot tool. No existing data has been changed.' }
}
$state = Initialize-DeploymentConfiguration -Workspace $workspace
Set-DeploymentProcessConfiguration -Values $state.Values
[Environment]::SetEnvironmentVariable('APP_BUILD_REVISION', 'restore-operation', 'Process')
$compose = @('compose', '--env-file', $state.Path, '--file', (Join-Path $PSScriptRoot 'compose.local.yaml'),
    '--profile', 'device', '--profile', 'production')

Write-Host 'Replacing the explicitly named deployment data volumes with the verified snapshot.' -ForegroundColor Yellow
& docker @compose down --remove-orphans
if ($LASTEXITCODE -ne 0) { throw 'Could not remove deployment containers. Existing data has not been changed.' }
$existingVolumes = @(& docker volume ls --format '{{.Name}}')
if ($LASTEXITCODE -ne 0) { throw 'Cannot list existing data volumes.' }
foreach ($archive in $manifest.archives) {
    $volume = "smart-charging-local_$([string]$archive.name)"
    # The allowlist above and this fixed prefix are the destructive-target boundary.
    if ($existingVolumes -contains $volume) {
        & docker volume rm $volume
        if ($LASTEXITCODE -ne 0) { throw "Cannot replace $volume. The source snapshot is still available." }
    }
    & docker volume create --label 'com.docker.compose.project=smart-charging-local' `
        --label "com.docker.compose.volume=$([string]$archive.name)" $volume | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Cannot create restore volume $volume." }
    & docker run --rm --pull never --network none --read-only `
        --mount "type=volume,source=$volume,target=/snapshot" `
        --mount "type=bind,source=$backupDirectory,target=/backup,readonly" `
        --entrypoint tar $snapshotImage --acls --xattrs --numeric-owner -xzf "/backup/$([string]$archive.file)" -C /snapshot
    if ($LASTEXITCODE -ne 0) { throw "Restore failed for $volume. Keep services stopped and retry the verified snapshot." }
}
$preservedEnvironment = Join-Path $workspace (".env.pre-restore-$([guid]::NewGuid().ToString('N'))")
Copy-Item -LiteralPath $state.Path -Destination $preservedEnvironment
Copy-Item -LiteralPath $backupEnvironment -Destination $state.Path -Force
Protect-GeneratedConfigurationPath -Path $preservedEnvironment
Protect-GeneratedConfigurationPath -Path $state.Path
foreach ($entry in $manifest.keyMaterial) {
    $targetDirectory = [string]$materialTargets[[string]$entry.directory]
    [void](New-Item -ItemType Directory -Path $targetDirectory -Force)
    & docker run --rm --pull never --network none --read-only `
        --mount "type=bind,source=$targetDirectory,target=/material" `
        --mount "type=bind,source=$backupDirectory,target=/backup,readonly" `
        --entrypoint tar $snapshotImage --acls --xattrs --numeric-owner -xzf "/backup/$([string]$entry.file)" -C /material
    if ($LASTEXITCODE -ne 0) { throw 'Could not restore key material. Keep services stopped and retain the source snapshot.' }
}
Write-Host 'Data and its original encryption keys were restored. Services remain stopped.' -ForegroundColor Green
Write-Host "The previous .env is preserved at $preservedEnvironment. Run docker-start.cmd, validate login and balances, then reopen traffic."
