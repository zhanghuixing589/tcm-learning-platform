param([ValidateSet('start','stop')][string]$Action='start',[int]$BackendPort,[int]$FrontendPort,[int]$AiPort,[string]$JavaHome='D:\develop\Java\jdk-17')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$servicesPath=Join-Path $projectRoot 'runtime\dev-services.json'
. (Join-Path $PSScriptRoot 'Stop-ProjectProcess.ps1')
if ($Action -eq 'stop') {
    if (Test-Path -LiteralPath $servicesPath) {
        $services=Get-Content -LiteralPath $servicesPath -Raw | ConvertFrom-Json
        foreach ($service in $services) {
            Stop-ProjectProcess $service
        }
        Remove-Item -LiteralPath $servicesPath
    }
    Write-Host 'Project app processes stopped. Stop the isolated database separately with python scripts/dev_mysql.py stop.'
    exit 0
}
. (Join-Path $PSScriptRoot 'Dev-Ports.ps1')
$launchLock=Enter-DevStartLock -ProjectRoot $projectRoot
try {
if (Test-Path -LiteralPath $servicesPath) { throw 'Project services are already recorded. Stop them before starting again.' }
$portOverrides=@{}
foreach ($name in @('BackendPort','FrontendPort','AiPort')) {
    if ($PSBoundParameters.ContainsKey($name)) { $portOverrides[$name]=$PSBoundParameters[$name] }
}
$launchPorts=Get-DevPorts -ProjectRoot $projectRoot -Overrides $portOverrides
$BackendPort=$launchPorts.BackendPort; $FrontendPort=$launchPorts.FrontendPort; $AiPort=$launchPorts.AiPort
foreach ($name in @('BackendPort','FrontendPort','AiPort')) {
    Assert-DevPortAvailable -Port $launchPorts[$name] -Service $name
}
$jar=Join-Path $projectRoot 'backend\target\tcm-platform-0.1.0.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build backend first: scripts/test-backend.ps1' }
if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'frontend\vite.config.ts'))) { throw 'Frontend config is missing. Restore frontend/vite.config.ts before starting services.' }
python (Join-Path $PSScriptRoot 'dev_mysql.py') start
if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL could not start' }
. (Join-Path $PSScriptRoot 'Import-DevEnvironment.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort -AiPort $AiPort
$services=@()
try {
    $backend=Start-Process -FilePath (Join-Path $JavaHome 'bin\java.exe') -ArgumentList @('-Duser.timezone=UTC','-jar',('"'+$jar+'"')) -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'runtime\backend.out.log') -RedirectStandardError (Join-Path $projectRoot 'runtime\backend.err.log') -PassThru
    $services+=@{id=$backend.Id;startedTicks=$backend.StartTime.ToUniversalTime().Ticks.ToString();service='backend'}
    $ai=Start-Process -FilePath (Join-Path $projectRoot 'ai-service\.venv\Scripts\python.exe') -ArgumentList @('-m','uvicorn','app.main:create_app','--factory','--host','127.0.0.1','--port',"$AiPort") -WorkingDirectory (Join-Path $projectRoot 'ai-service') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'runtime\ai.out.log') -RedirectStandardError (Join-Path $projectRoot 'runtime\ai.err.log') -PassThru
    $services+=@{id=$ai.Id;startedTicks=$ai.StartTime.ToUniversalTime().Ticks.ToString();service='ai'}
    $front=Start-Process -FilePath (Get-Command node).Source -ArgumentList @('node_modules/vite/bin/vite.js','--config','vite.config.ts','--host','127.0.0.1','--port',"$FrontendPort",'--strictPort') -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'runtime\frontend.out.log') -RedirectStandardError (Join-Path $projectRoot 'runtime\frontend.err.log') -PassThru
    $services+=@{id=$front.Id;startedTicks=$front.StartTime.ToUniversalTime().Ticks.ToString();service='frontend'}
    $services | ConvertTo-Json | Set-Content -LiteralPath $servicesPath -Encoding UTF8
    $deadline=(Get-Date).AddSeconds(180)
    do {
        try { $health=Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/actuator/health/readiness" -TimeoutSec 2; if ($health.status -eq 'UP') { break } } catch {}
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if (!$health -or $health.status -ne 'UP') { throw 'Backend readiness timed out; see runtime/backend.err.log and backend.out.log' }
    $aiHealth=Invoke-RestMethod -Uri "http://127.0.0.1:$AiPort/internal/v1/health/readiness" -Headers @{'X-Internal-Token'=$env:AI_INTERNAL_TOKEN} -TimeoutSec 5
    if ($aiHealth.data.status -ne 'UP') { throw 'AI service health failed' }
    foreach ($service in $services) {
        $startedProcess=Get-Process -Id $service.id -ErrorAction SilentlyContinue
        if (!$startedProcess -or $startedProcess.StartTime.ToUniversalTime().Ticks.ToString() -ne $service.startedTicks) { throw "$($service.service) exited during startup; inspect its runtime log" }
    }
    $frontendHealth=Invoke-WebRequest -Uri "http://127.0.0.1:$FrontendPort/" -UseBasicParsing -TimeoutSec 5
    if ($frontendHealth.StatusCode -ne 200) { throw 'Frontend health failed' }
    $descendants=@()
    foreach ($service in $services) {
        foreach ($child in (Get-CimInstance Win32_Process -Filter "ParentProcessId=$($service.id)" -ErrorAction SilentlyContinue)) {
            $childProcess=Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
            if ($childProcess) { $descendants+=@{id=$childProcess.Id;startedTicks=$childProcess.StartTime.ToUniversalTime().Ticks.ToString();service=($service.service+'-child')} }
        }
    }
    @($services+$descendants) | ConvertTo-Json | Set-Content -LiteralPath $servicesPath -Encoding UTF8
    Write-Host "Ready: http://127.0.0.1:$FrontendPort | backend:$BackendPort | internal AI:$AiPort"
} catch {
    foreach ($service in $services) { Stop-ProjectProcess $service }
    if (Test-Path -LiteralPath $servicesPath) { Remove-Item -LiteralPath $servicesPath }
    throw
}
} finally { $launchLock.Dispose() }
