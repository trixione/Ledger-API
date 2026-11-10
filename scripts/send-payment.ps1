param(
    [Parameter(Mandatory)] [guid] $MerchantAccountId,
    [ValidateSet('EUR', 'USD', 'JPY')] [string] $Currency = 'EUR',
    [string] $Amount = '125.50',
    [string] $PaymentId = [guid]::NewGuid().ToString(),
    [string] $EventId = [guid]::NewGuid().ToString(),
    [string] $ManagementUrl = 'http://127.0.0.1:15672'
)

$ErrorActionPreference = 'Stop'
$credentials = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('ledger:ledger-local'))
$payload = @{
    eventId = $EventId
    paymentId = $PaymentId
    merchantAccountId = $MerchantAccountId.ToString()
    amount = $Amount
    currency = $Currency
} | ConvertTo-Json -Compress
$request = @{
    properties = @{ content_type = 'application/json'; delivery_mode = 2 }
    routing_key = 'payment.settled.v1'
    payload = $payload
    payload_encoding = 'string'
} | ConvertTo-Json -Compress
$result = Invoke-RestMethod "$ManagementUrl/api/exchanges/%2F/ledger.payments/publish" -Method Post `
    -Headers @{ Authorization = "Basic $credentials" } -ContentType 'application/json' -Body $request
if (!$result.routed) {
    throw 'RabbitMQ did not route the message. Check that the Ledger API has declared the queue.'
}
[pscustomobject]@{ PaymentId = $PaymentId; EventId = $EventId; Routed = $true }
