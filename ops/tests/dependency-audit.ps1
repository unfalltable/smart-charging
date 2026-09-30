[CmdletBinding()]
param(
    [string]$BomPath = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($BomPath)) {
    $BomPath = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'target/bom.json'
}
# Only public package coordinates are sent; application configuration is never read.
if (-not (Test-Path -LiteralPath $BomPath -PathType Leaf)) {
    throw 'Backend SBOM is missing. Generate target/bom.json with CycloneDX before the dependency audit.'
}
$bom = Get-Content -LiteralPath $BomPath -Raw | ConvertFrom-Json
if ($bom.bomFormat -ne 'CycloneDX') { throw 'Expected a CycloneDX dependency inventory.' }
$components = @($bom.components | Where-Object {
    $_.purl -like 'pkg:maven/*' -and $_.purl -notlike 'pkg:maven/io.smartcharge.platform/*'
})
if ($components.Count -eq 0) { throw 'The SBOM contains no external Maven dependencies; refuse an empty audit.' }
if ($components.Count -gt 1000) { throw 'Dependency inventory exceeds this audit batch limit.' }
$queries = @($components | ForEach-Object { @{ package = @{ purl = $_.purl } } })
$response = Invoke-RestMethod -Uri 'https://api.osv.dev/v1/querybatch' -Method Post `
    -ContentType 'application/json' -Body (@{ queries = $queries } | ConvertTo-Json -Depth 6) -TimeoutSec 45
if (@($response.results).Count -ne $components.Count) {
    throw 'The vulnerability service returned an incomplete result; the audit is not valid.'
}
$findings = @()
for ($index = 0; $index -lt $components.Count; $index++) {
    foreach ($vulnerability in @($response.results[$index].vulns)) {
        if ($null -ne $vulnerability) {
            $findings += [pscustomobject]@{
                Dependency = $components[$index].purl
                Advisory = $vulnerability.id
            }
        }
    }
}
if ($findings.Count -gt 0) {
    $findings | Format-Table -AutoSize | Out-Host
    throw "Dependency audit failed: $($findings.Count) known advisories affect the resolved backend inventory."
}
Write-Host "PASS: OSV checked $($components.Count) resolved backend dependencies; no known advisories returned."
Write-Host 'This is a point-in-time dependency check, not a security or production certification.'
