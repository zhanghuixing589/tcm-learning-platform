param([string]$Maven = 'D:\develop\apache-maven-3.9.9\bin\mvn.cmd', [string]$JavaHome = 'D:\develop\Java\jdk-17', [string]$TestPattern)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# A persisted state file may outlive the process after a reboot. The idempotent
# launcher probes readiness and restarts the recorded data directory if needed.
python (Join-Path $PSScriptRoot 'dev_mysql.py') start
if ($LASTEXITCODE -ne 0) { throw 'Temporary MySQL failed to start' }
$state = Get-Content -LiteralPath (Join-Path $projectRoot 'runtime\dev-mysql.json') -Raw | ConvertFrom-Json
$env:DB_URL = "jdbc:mysql://127.0.0.1:$($state.port)/tcm_stage_one?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
$env:DB_USERNAME = 'tcm_stage'
$env:DB_PASSWORD = $state.password
$env:JWT_SECRET = [guid]::NewGuid().ToString() + [guid]::NewGuid().ToString()
$env:AI_INTERNAL_TOKEN = [guid]::NewGuid().ToString() + [guid]::NewGuid().ToString()
$env:COOKIE_SECURE = 'false'
$env:JAVA_HOME = $JavaHome
Push-Location (Join-Path $projectRoot 'backend')
try {
    if ($TestPattern) { & $Maven -B "-Dtest=$TestPattern" test } else { & $Maven -B package }
    $result = $LASTEXITCODE
} finally { Pop-Location }
exit $result
