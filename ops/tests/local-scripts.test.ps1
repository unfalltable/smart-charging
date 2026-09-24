$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$previousSecret = $env:QR_SIGNING_SECRET
try {
    $env:QR_SIGNING_SECRET = 'verification-only-secret-000000000000000'
    $qr = & (Join-Path $workspace 'ops/generate-pilot-qr.ps1')
    $expected = 'sc1.11111111-1111-1111-1111-111111111111.44444444-4444-4444-4444-000000000001.253f1c15b7eb5ffdc4739271da4251be9ed2c24f462d90683c1921c70df5c654'
    if ($qr -ne $expected) { throw 'QR signature does not match the independently calculated HMAC vector.' }
}
finally { $env:QR_SIGNING_SECRET = $previousSecret }

function Test-DemoContract([bool]$ReflectPayment) {
    $state = @{ reads = 0; paymentRequested = $false; completed = $false }
    $fixtureOrderId = '77777777-7777-7777-7777-777777777777'
    $fixturePaymentId = '88888888-8888-8888-8888-888888888888'
    function Start-Sleep { param([int]$Seconds) }
    function Invoke-RestMethod {
        param($Uri, $Method, $Headers, $TimeoutSec, $ContentType, $Body)
        if ($Headers['X-Tenant-Id'] -ne '11111111-1111-1111-1111-111111111111') {
            throw 'Missing tenant header.'
        }
        switch -Wildcard ($Uri) {
            '*/admin/assets/devices' { return @([pscustomobject]@{ deviceCode = 'PILE001'; status = 'ONLINE' }) }
            '*/charging/orders/mine' {
                $state.reads++
                return @(
                    [pscustomobject]@{ orderId = 'unrelated-order'; status = 'COMPLETED'; paidAmountMinor = 99; payableAmountMinor = 99 }
                    [pscustomobject]@{
                        orderId = $fixtureOrderId
                        status = $(if ($state.reads -eq 1) { 'CHARGING' } else { 'COMPLETED' })
                        energyWh = 350; payableAmountMinor = 1
                        paidAmountMinor = $(if ($state.completed -and $ReflectPayment) { 1 } else { 0 })
                    }
                )
            }
            '*/charging/orders' {
                if ($Method -ne 'Post' -or -not $Headers['Idempotency-Key']) { throw 'Invalid order request.' }
                return [pscustomobject]@{ orderId = $fixtureOrderId; orderNo = 'CONTRACT-TEST'; status = 'START_PENDING' }
            }
            '*/payments' {
                $request = $Body | ConvertFrom-Json
                if ($request.orderId -ne $fixtureOrderId -or $request.channel -ne 'WECHAT') {
                    throw 'Payment does not target the created order.'
                }
                $state.paymentRequested = $true
                return [pscustomobject]@{
                    paymentId = $fixturePaymentId; clientParameters = @{ mode = 'LOCAL_SIMULATION' }
                }
            }
            "*/payments/$fixturePaymentId/simulate-success" {
                $state.completed = $true
                return [pscustomobject]@{ paymentId = $fixturePaymentId; status = 'SUCCEEDED' }
            }
            default { throw "Unexpected API request: $Uri" }
        }
    }

    $failure = $null
    try {
        & (Join-Path $workspace 'ops/demo-charge.ps1') -TimeoutSeconds 1 `
            -EnvironmentFile (Join-Path $workspace '.env.docker.example')
    }
    catch { $failure = $_.Exception.Message }
    if ($ReflectPayment -and $failure) { throw $failure }
    if (-not $ReflectPayment -and $failure -ne 'Payment was not reflected in the order balance.') {
        throw 'Demo must reject an uncredited payment.'
    }
    if (-not $state.paymentRequested -or -not $state.completed -or $state.reads -lt 3) {
        throw 'Demo did not verify the complete order/payment contract.'
    }
}

Test-DemoContract -ReflectPayment $true
Test-DemoContract -ReflectPayment $false
Write-Host 'PASS: QR signature, orderId contract, payment completion and unpaid-order rejection.'
