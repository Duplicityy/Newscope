$ErrorActionPreference = 'Stop'
function Test-Handshake([int]$Port, [byte[]]$Request, [int]$ExpectedLength, [int]$ExpectedCode) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $client.ReceiveTimeout = 10000
        $client.Connect('127.0.0.1', $Port)
        $stream = $client.GetStream()
        $stream.Write($Request, 0, $Request.Length)
        $response = New-Object byte[] $ExpectedLength
        $received = 0
        while ($received -lt $ExpectedLength) {
            $count = $stream.Read($response, $received, $ExpectedLength - $received)
            if ($count -eq 0) { throw "Port $Port closed before responding." }
            $received += $count
        }
        if ($response[0] -ne $ExpectedCode) { throw "Port $Port returned $($response[0]), expected $ExpectedCode." }
        [pscustomobject]@{ Port = $Port; ResponseCode = $response[0]; Bytes = $received; Passed = $true }
    } finally { $client.Dispose() }
}
$results = @(
    (Test-Handshake 7304 ([byte[]]@(15, 0, 0, 0, 184)) 1 0),
    (Test-Handshake 7304 ([byte[]]@(15, 0, 0, 0, 183)) 1 6),
    (Test-Handshake 13302 ([byte[]]@(14)) 9 0)
)
$results | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'local-runtime/verification.json')
$results | Format-Table
