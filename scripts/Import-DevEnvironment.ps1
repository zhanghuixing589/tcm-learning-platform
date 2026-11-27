param([int]$BackendPort,[int]$FrontendPort,[int]$AiPort)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Dev-Ports.ps1')
$environmentPortOverrides=@{}
foreach ($name in @('BackendPort','FrontendPort','AiPort')) {
    if ($PSBoundParameters.ContainsKey($name)) { $environmentPortOverrides[$name]=$PSBoundParameters[$name] }
}
$environmentPorts=Get-DevPorts -ProjectRoot $projectRoot -Overrides $environmentPortOverrides
$BackendPort=$environmentPorts.BackendPort; $FrontendPort=$environmentPorts.FrontendPort; $AiPort=$environmentPorts.AiPort
$statePath=Join-Path $projectRoot 'runtime\dev-mysql.json'
if (!(Test-Path -LiteralPath $statePath)) { throw 'Run python scripts/dev_mysql.py start first' }
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$secretPath=Join-Path $projectRoot 'runtime\dev-secrets.json'
if (!(Test-Path -LiteralPath $secretPath)) {
    @{jwt=([guid]::NewGuid().ToString()+[guid]::NewGuid().ToString());internal=([guid]::NewGuid().ToString()+[guid]::NewGuid().ToString())} | ConvertTo-Json | Set-Content -LiteralPath $secretPath -Encoding UTF8
}
$secrets=Get-Content -LiteralPath $secretPath -Raw | ConvertFrom-Json
$env:DB_URL="jdbc:mysql://127.0.0.1:$($state.port)/tcm_stage_one?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
$env:DB_USERNAME='tcm_stage'; $env:DB_PASSWORD=$state.password
$env:JWT_SECRET=$secrets.jwt; $env:AI_INTERNAL_TOKEN=$secrets.internal
$env:COOKIE_SECURE='false'; $env:SERVER_PORT="$BackendPort"
$env:SERVER_ADDRESS='127.0.0.1'
$env:ALLOWED_ORIGINS="http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort"
$env:AI_BASE_URL="http://127.0.0.1:$AiPort"; $env:BACKEND_URL="http://127.0.0.1:$BackendPort"
$env:AI_BACKEND_URL="http://127.0.0.1:$BackendPort"
$env:AVATAR_STORAGE_DIR=Join-Path $projectRoot 'runtime\avatars'
$env:SOURCE_STORAGE_DIR=Join-Path $projectRoot 'runtime\source-files'
# Import only the restricted application identity; root credentials never enter Java.
$minioEnvPath=Join-Path $projectRoot 'runtime\minio.env'
if (Test-Path -LiteralPath $minioEnvPath) {
    foreach ($minioLine in [System.IO.File]::ReadAllLines($minioEnvPath)) {
        if ($minioLine -match '^(TCM_MINIO_APP_ACCESS_KEY|TCM_MINIO_APP_SECRET_KEY)=(.+)$') {
            [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
        }
    }
}
$env:TCM_MINIO_ENDPOINT='http://127.0.0.1:19000'
$env:TCM_MINIO_BUCKET='tcm-learning-sources'
