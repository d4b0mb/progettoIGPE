# staff-curl-guard.ps1
# PreToolUse guard for the Bash tool of the demo staff agents (chef, head-waiter, dish-runner).
# ALLOWLIST: a Bash call is permitted only if it is exactly one of
#   sleep <1-120>
#   date [ +FORMAT ]            (optionally prefixed TZ=Europe/Rome)
#   curl|curl.exe <allowed options> http://localhost:8080/api/v1/...
# Everything else is denied (exit 2). Any parse error fails CLOSED.
# Allowed calls exit 0 with no decision, so the normal permission flow still applies.
# Usage: powershell.exe -NoProfile -ExecutionPolicy Bypass -File staff-curl-guard.ps1 -Role chef|head-waiter|dish-runner
param([string]$Role = '')

$ErrorActionPreference = 'Stop'

function Deny([string]$reason) {
    $msg = 'Staff API guard (' + $Role + '): ' + $reason + '. Only single curl calls to http://localhost:8080/api/v1, sleep N, or date are allowed. Do NOT retry a variant; record the action as failed and move on.'
    $obj = @{ hookSpecificOutput = @{ hookEventName = 'PreToolUse'; permissionDecision = 'deny'; permissionDecisionReason = $msg } }
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 4))
    [Console]::Error.WriteLine($msg)
    exit 2
}

try {
    if ($Role -notin @('chef', 'head-waiter', 'dish-runner')) { Deny 'unknown or missing -Role (fail closed)' }

    $raw = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($raw)) { Deny 'empty hook input (fail closed)' }
    $j = $raw | ConvertFrom-Json
    $cmd = $null
    if ($null -ne $j.tool_input) { $cmd = $j.tool_input.command }
    if ($null -eq $cmd -or [string]::IsNullOrWhiteSpace([string]$cmd)) { Deny 'no tool_input.command (fail closed)' }
    $cmd = ([string]$cmd).Trim()

    # No expansion, substitution, or multi-line commands anywhere.
    if ($cmd -match '[\r\n`$\\]') { Deny 'newline, backtick, $ or backslash is not allowed (paste values literally)' }

    # Shell metacharacters outside quotes.
    $unquoted = [regex]::Replace($cmd, "'[^']*'|""[^""]*""", "Q")
    if ($unquoted -match "['""]") { Deny 'unbalanced quotes (no apostrophes inside a single-quoted JSON body)' }
    if ($unquoted -match '[;&|<>(){}*?\[\]~!#]') { Deny 'shell operator or glob outside quotes' }

    if ($cmd -cmatch '^sleep\s+([1-9][0-9]?|1[01][0-9]|120)$') { exit 0 }
    if ($cmd -cmatch "^(TZ=Europe/Rome\s+)?date(\s+'\+[A-Za-z0-9%:_ ./-]*')?$") { exit 0 }

    # Tokenize: 'single', "double", or bare.
    $tokens = @()
    foreach ($m in [regex]::Matches($cmd, "'([^']*)'|""([^""]*)""|(\S+)")) {
        if ($m.Groups[1].Success) { $tokens += $m.Groups[1].Value }
        elseif ($m.Groups[2].Success) { $tokens += $m.Groups[2].Value }
        else { $tokens += $m.Groups[3].Value }
    }
    if ($tokens.Count -lt 2 -or $tokens[0] -cnotin @('curl', 'curl.exe')) { Deny 'command is not sleep, date, or curl' }

    $flagOnly  = @('-s', '-S', '-sS', '-Ss', '-i', '--silent', '--show-error', '--include')
    $withValue = @('-X', '--request', '-H', '--header', '-d', '--data', '--data-raw', '-w', '--write-out', '-m', '--max-time', '--connect-timeout')
    $method = 'GET'
    $urls = @()
    $hasData = $false
    for ($i = 1; $i -lt $tokens.Count; $i++) {
        $t = $tokens[$i]
        if ($t -cin $flagOnly) { continue }
        if ($t -cin $withValue) {
            if ($i + 1 -ge $tokens.Count) { Deny ('option ' + $t + ' has no value') }
            $v = $tokens[++$i]
            if ($t -cin @('-X', '--request')) {
                if ($v -cnotin @('GET', 'POST', 'PUT', 'PATCH', 'DELETE')) { Deny 'unsupported HTTP method' }
                $method = $v
            }
            if ($t -cin @('-d', '--data', '--data-raw')) {
                if ($v.StartsWith('@')) { Deny 'reading a request body from a file is not allowed' }
                $hasData = $true
            }
            if ($t -cin @('-H', '--header')) {
                if ($v.StartsWith('@')) { Deny 'reading headers from a file is not allowed' }
            }
            if ($t -cin @('-w', '--write-out')) {
                if ($v.StartsWith('@') -or $v -match '%output') { Deny 'write-out may not read or write files' }
            }
            if ($t -cin @('-m', '--max-time', '--connect-timeout') -and $v -notmatch '^[0-9]{1,3}$') { Deny 'timeout must be an integer' }
            continue
        }
        if ($t.StartsWith('-')) { Deny ('curl option not allowed: ' + $t) }
        $urls += $t
    }
    if ($urls.Count -ne 1) { Deny 'exactly one URL is required' }
    $url = $urls[0]
    # Path: letters, digits, / _ - only (no %, no dots, so no encoded or literal traversal). Query may use more.
    if ($url -cnotmatch '^http://localhost:8080/api/v1/[A-Za-z0-9/_-]*(\?[A-Za-z0-9/_.=&%:+-]*)?$') { Deny 'URL must be http://localhost:8080/api/v1/... with a plain path (no percent-encoding or dots)' }
    if ($hasData -and $method -eq 'GET') { $method = 'POST' }

    $path = ($url -replace '^http://localhost:8080', '') -replace '\?.*$', ''
    if ($path -match '(?i)/staff|/recovery|password|assistant') { Deny 'staff, recovery, password and assistant endpoints are forbidden' }

    $isLogin = ($method -eq 'POST' -and $path -ceq '/api/v1/auth/login')
    if ($isLogin) { exit 0 }

    $uuid = '[0-9a-fA-F-]{36}'
    switch ($Role) {
        'chef' {
            if ($method -eq 'GET' -and $path -ceq '/api/v1/orders/active') { exit 0 }
            if ($method -eq 'PATCH' -and $path -cmatch ('^/api/v1/orders/' + $uuid + '/status$')) { exit 0 }
            Deny 'chef may only GET /orders/active and PATCH /orders/{id}/status'
        }
        'dish-runner' {
            if ($method -eq 'GET') { exit 0 }
            if ($method -eq 'POST' -and $path -cin @('/api/v1/reservations', '/api/v1/orders')) { exit 0 }
            if ($method -eq 'PATCH' -and $path -cmatch ('^/api/v1/(reservations|orders)/' + $uuid + '/status$')) { exit 0 }
            Deny 'dish-runner may only read, create reservations/orders, and PATCH reservation/order status'
        }
        'head-waiter' {
            if ($method -eq 'GET') { exit 0 }
            if ($method -eq 'POST' -and $path -cin @('/api/v1/reservations', '/api/v1/orders')) { exit 0 }
            if ($method -eq 'PATCH' -and $path -cmatch ('^/api/v1/(reservations|orders)/' + $uuid + '(/status|/priority)?$')) { exit 0 }
            if ($method -in @('PUT', 'DELETE') -and $path -cmatch ('^/api/v1/reservations/' + $uuid + '$')) { exit 0 }
            if ($method -eq 'PUT' -and $path -cmatch ('^/api/v1/orders/' + $uuid + '$')) { exit 0 }
            Deny 'head-waiter may only read and change reservations and orders'
        }
    }
    Deny 'no rule matched (fail closed)'
} catch {
    Deny ('internal guard error, failing closed: ' + $_.Exception.Message)
}
