# Shared by the launcher, environment importer and read-only environment check.
function Get-DevPorts {
    param([string]$ProjectRoot, [hashtable]$Overrides=@{})
    $names=@('BackendPort','FrontendPort','AiPort')
    $resolved=@{BackendPort=8084;FrontendPort=5177;AiPort=8004}
    $profile=Join-Path $ProjectRoot 'runtime/dev-ports.json'
    if (Test-Path -LiteralPath $profile) {
        $values=Get-Content -LiteralPath $profile -Raw | ConvertFrom-Json
        foreach ($name in $names) {
            if (!$values.PSObject.Properties[$name]) { throw "Missing $name in $profile" }
            $resolved[$name]=$values.$name
        }
    } elseif ((Test-Path -LiteralPath (Join-Path $ProjectRoot '.git') -PathType Leaf) -and @($names | Where-Object { !$Overrides.ContainsKey($_) }).Count -gt 0) {
        throw 'Linked worktree requires runtime/dev-ports.json or all three explicit port parameters. Reserve distinct ports; see docs/workflow/README.md.'
    }
    foreach ($name in $names) {
        if ($Overrides.ContainsKey($name)) { $resolved[$name]=$Overrides[$name] }
        $value=$resolved[$name]
        if ($value -isnot [int] -and $value -isnot [long]) { throw "$name must be an integer" }
        if ($value -lt 1024 -or $value -gt 65535) { throw "$name must be between 1024 and 65535" }
        $resolved[$name]=[int]$value
    }
    if (@($resolved.Values | Sort-Object -Unique).Count -ne 3) { throw 'BackendPort, FrontendPort and AiPort must be distinct' }
    return $resolved
}

function Assert-DevPortAvailable {
    param([int]$Port,[string]$Service)
    $listener=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,$Port)
    # Match the actual IPv4 loopback used by Java, Vite and uvicorn.
    $listener.Server.ExclusiveAddressUse=$true
    try { $listener.Start() }
    catch {
        $owners=@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
        throw "$Service cannot bind 127.0.0.1:$Port (listener PIDs: $($owners -join ',')). Inspect the owner or assign explicit ports; ports are never auto-incremented."
    } finally { $listener.Stop() }
}

function Enter-DevStartLock {
    param([string]$ProjectRoot)
    $directory=Join-Path $ProjectRoot 'runtime'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    try { return [System.IO.File]::Open((Join-Path $directory 'dev-start.lock'),[System.IO.FileMode]::OpenOrCreate,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None) }
    catch { throw 'Another launcher is starting services in this worktree. Wait for it to finish before retrying.' }
}
