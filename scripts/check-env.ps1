[CmdletBinding()]
param(
    [string]$JavaHome = 'D:\develop\Java\jdk-17',
    [string]$Maven = 'D:\develop\apache-maven-3.9.9\bin\mvn.cmd',
    [string]$Python = (Join-Path $PSScriptRoot '../ai-service/.venv/Scripts/python.exe'),
    [string]$MySqlBin = 'D:\develop\mysql8.0\bin',
    [int[]]$Ports
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Dev-Ports.ps1')
if (!$PSBoundParameters.ContainsKey('Ports')) {
    $diagnosticPorts=Get-DevPorts -ProjectRoot $root
    $Ports=@($diagnosticPorts.BackendPort,$diagnosticPorts.FrontendPort,$diagnosticPorts.AiPort)
}
$checks = @()
function Probe([string]$Name,[scriptblock]$Action) {
    try { $detail = & $Action; $script:checks += [pscustomobject]@{ name=$Name; status='ok'; detail=($detail -join ' ') } }
    catch { $script:checks += [pscustomobject]@{ name=$Name; status='failed'; detail=$_.Exception.Message } }
}
Probe 'git' { & git -C $root rev-parse --show-toplevel; if ($LASTEXITCODE -ne 0) { throw 'Not a Git checkout' } }
Probe 'jdk' { $v = (& (Join-Path $JavaHome 'bin/java.exe') -version 2>&1 | Out-String); if ($LASTEXITCODE -ne 0 -or $v -notmatch 'version "17\.') { throw 'Configured JDK must be 17' }; $v.Trim() }
Probe 'maven' {
    $previousJavaHome = $env:JAVA_HOME
    try { $env:JAVA_HOME=$JavaHome; $v=(& $Maven -v 2>&1 | Out-String); if ($LASTEXITCODE -ne 0 -or $v -notmatch 'Apache Maven') { throw 'Configured Maven is unavailable' }; ($v -split "`n")[0] }
    finally { $env:JAVA_HOME=$previousJavaHome }
}
Probe 'node' { $v=& node -p 'process.versions.node'; if ($LASTEXITCODE -ne 0) { throw 'Node unavailable' }; $n=[version]$v; if (!(($n.Major -eq 20 -and $n.Minor -ge 19) -or ($n.Major -ge 22 -and ($n.Major -gt 22 -or $n.Minor -ge 12)))) { throw 'Vite 7 requires Node 20.19+ or 22.12+' }; $v }
Probe 'npm' { & npm.cmd --version; if ($LASTEXITCODE -ne 0) { throw 'npm unavailable' } }
Probe 'frontend-dependencies' { if (!(Test-Path -LiteralPath (Join-Path $root 'frontend/node_modules/.bin/vitest.cmd'))) { throw 'Run npm ci in frontend' }; 'vitest installed; lockfile remains authoritative' }
Probe 'python' { & $Python -c 'import sys; assert sys.version_info[:2] == (3,11), sys.version; import fastapi, pytest, pydantic_settings; print(sys.version.split()[0])'; if ($LASTEXITCODE -ne 0) { throw 'Project Python 3.11 or dependencies missing' } }
Probe 'mysql-binary' { & (Join-Path $MySqlBin 'mysqld.exe') --version; if ($LASTEXITCODE -ne 0) { throw 'MySQL binary unavailable' } }
Probe 'isolated-mysql-state' {
    $stateFile=Join-Path $root 'runtime/dev-mysql.json'
    if (!(Test-Path -LiteralPath $stateFile)) { throw 'No isolated DB state; use scripts/dev_mysql.py start when backend tests are needed' }
    $state=Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    $client=New-Object System.Net.Sockets.TcpClient
    try { $pending=$client.ConnectAsync('127.0.0.1',[int]$state.port); if (!$pending.Wait(1500) -or !$client.Connected) { throw 'Recorded isolated DB port is not listening' }; "127.0.0.1:$($state.port) listening (not SQL authentication/readiness proof)" }
    finally { $client.Dispose() }
}
foreach ($port in $Ports) {
    $listener=New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback,$port)
    try { $listener.Start(); $checks += [pscustomobject]@{name="port-$port";status='info';detail='free'} }
    catch { $checks += [pscustomobject]@{name="port-$port";status='info';detail='occupied; inspect recorded project process before restart'} }
    finally { $listener.Stop() }
}
$checks | ConvertTo-Json -Depth 4
if (@($checks | Where-Object status -eq 'failed').Count -gt 0) { exit 1 }
