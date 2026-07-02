function Send-RawHttp($url) {
    $uri = [System.Uri]$url
    $host_ = $uri.Host
    $port = if ($uri.Port -ne -1) { $uri.Port } else { 80 }
    $path = $uri.PathAndQuery
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect($host_, $port)
        $stream = $client.GetStream()
        $req = "GET $path HTTP/1.1`r`nHost: $host_`r`nConnection: close`r`n`r`n"
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($req)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        $resp = $reader.ReadToEnd()
        $client.Close()
        return $resp
    } catch {
        $client.Close()
        throw $_
    }
}

$urls = @(
    'http://localhost:8080/api/statistics',
    'http://localhost:8080/api/config/scheduler-logs',
    'http://localhost:8080/api/statistics/by-city',
    'http://localhost:8080/api/statistics/by-type'
)
foreach ($u in $urls) {
    try {
        $r = Send-RawHttp $u
        $idx = $r.IndexOf("`r`n`r`n")
        $header = $r.Substring(0, [Math]::Min(80, $idx))
        $body = $r.Substring($idx + 4)
        $bodyShow = if ($body.Length -gt 400) { $body.Substring(0, 400) } else { $body }
        Write-Host "[OK] $u"
        Write-Host "  HEAD: $header"
        Write-Host "  BODY: $bodyShow"
    } catch {
        Write-Host "[FAIL] $u"
        Write-Host "  ERR: $($_.Exception.Message)"
    }
}
