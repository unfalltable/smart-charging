$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$restoreScript = Join-Path $workspace 'ops/restore.ps1'
$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ("charging-restore-test-$([guid]::NewGuid().ToString('N'))")
[void](New-Item -ItemType Directory -Path $testDirectory)
function Assert-RestoreRejected {
    param([string]$Expected, [switch]$Confirmed)
    try {
        & $restoreScript -BackupPath $testDirectory -ConfirmRestore:$Confirmed
    }
    catch {
        if ($_.Exception.Message -notmatch $Expected) { throw }
        return
    }
    throw 'A dangerous or incomplete restore was unexpectedly accepted.'
}
try {
    Assert-RestoreRejected -Expected 'repeat with -ConfirmRestore'
    $manifest = [ordered]@{
        format = 1; project = 'smart-charging-local'; postgresMajor = 18
        archives = @([ordered]@{ name = '../../unrelated-volume'; file = 'outside.tar.gz'; sha256 = 'bad' })
        keyMaterial = @()
    }
    [IO.File]::WriteAllText((Join-Path $testDirectory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6))
    Assert-RestoreRejected -Expected 'unexpected or duplicate data volume' -Confirmed
    $manifest.archives = @([ordered]@{ name = 'postgres-data'; file = 'postgres-data.tar.gz'; sha256 = 'bad' })
    [IO.File]::WriteAllText((Join-Path $testDirectory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6))
    [IO.File]::WriteAllText((Join-Path $testDirectory 'postgres-data.tar.gz'), 'corrupt archive')
    Assert-RestoreRejected -Expected 'bad checksum' -Confirmed
    $manifest.archives = @()
    foreach ($name in @('postgres-data', 'valkey-data', 'nats-data')) {
        $archivePath = Join-Path $testDirectory "$name.tar.gz"
        [IO.File]::WriteAllText($archivePath, 'preflight-only fixture')
        $manifest.archives += [ordered]@{ name = $name; file = "$name.tar.gz"; sha256 = (Get-FileHash -LiteralPath $archivePath).Hash }
    }
    $environmentPath = Join-Path $testDirectory 'deployment.env'
    [IO.File]::WriteAllText($environmentPath, "WECHAT_PAYMENT_DIRECTORY='$($workspace -replace '\\', '/')'`n")
    $manifest.environmentSha256 = (Get-FileHash -LiteralPath $environmentPath).Hash
    $materialPath = Join-Path $testDirectory 'key-material-wechat-pay.tar.gz'
    [IO.File]::WriteAllText($materialPath, 'preflight-only fixture')
    $manifest.keyMaterial = @([ordered]@{
        directory = 'wechat-pay'; configuration = 'WECHAT_PAYMENT_DIRECTORY'; file = 'key-material-wechat-pay.tar.gz'
        sha256 = (Get-FileHash -LiteralPath $materialPath).Hash
    })
    [IO.File]::WriteAllText((Join-Path $testDirectory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6))
    Assert-RestoreRejected -Expected 'filesystem or workspace root' -Confirmed
    $manifest.keyMaterial[0].configuration = 'DEVICE_TLS_DIRECTORY'
    [IO.File]::WriteAllText((Join-Path $testDirectory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6))
    Assert-RestoreRejected -Expected 'key-material metadata is invalid' -Confirmed
    Write-Host 'PASS: restore requires explicit confirmation and rejects unrelated volumes, corrupt archives and unsafe key destinations before Docker or local configuration changes.'
}
finally {
    $resolved = [IO.Path]::GetFullPath($testDirectory)
    $prefix = [IO.Path]::GetFullPath(([IO.Path]::GetTempPath())).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolved).StartsWith('charging-restore-test-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
