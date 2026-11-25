function Invoke-TestPlan {
    param($Plan, [switch]$PlanOnly,
        [string]$JavaHome = 'D:\develop\Java\jdk-17',
        [string]$Maven = 'D:\develop\apache-maven-3.9.9\bin\mvn.cmd',
        [string]$Python = (Join-Path $PSScriptRoot '../ai-service/.venv/Scripts/python.exe'),
        [string]$EvidenceRoot = (Join-Path $PSScriptRoot '../runtime/workflow'))
    if ($PlanOnly) { $Plan | ConvertTo-Json -Depth 8; return }
    $root = Split-Path -Parent $PSScriptRoot
    $logDirectory = Join-Path $EvidenceRoot ((Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $Plan | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $logDirectory 'plan.json') -Encoding UTF8
    $report = [ordered]@{ started = (Get-Date).ToUniversalTime().ToString('o'); head = (& git -C $root rev-parse HEAD); status = 'running'; steps = @(); manual = $Plan.manual; missingCoverage = $Plan.missingCoverage; environment = @{ javaHome=$JavaHome; maven=$Maven; python=$Python } }
    $shell = (Get-Process -Id $PID).Path
    function Run-Step([string]$Name, [string]$Program, [string[]]$Arguments, [string]$Directory) {
        $step = [ordered]@{ name=$Name; command=@($Program)+$Arguments; started=(Get-Date).ToUniversalTime().ToString('o'); exitCode=$null; status='running' }
        $report.steps += $step
        Push-Location $Directory
        try {
            & $Program @Arguments 2>&1 | Tee-Object -FilePath (Join-Path $logDirectory "$Name.log") | Out-Host
            $step.exitCode = $LASTEXITCODE
            if ($LASTEXITCODE -ne 0) { $step.status='failed'; throw "$Name failed with exit code $LASTEXITCODE" }
            $step.status='passed'
        } finally { $step.finished=(Get-Date).ToUniversalTime().ToString('o'); Pop-Location }
    }
    try {
        if ($Plan.workflow) { Run-Step 'workflow' $shell @('-NoProfile','-File',(Join-Path $PSScriptRoot 'tests/workflow.Tests.ps1')) $root }
        if ($Plan.backend.Count -gt 0) {
            # Child shell contains legacy test-backend.ps1's exit and environment changes.
            Run-Step 'backend' $shell @('-NoProfile','-File',(Join-Path $PSScriptRoot 'test-backend.ps1'),'-JavaHome',$JavaHome,'-Maven',$Maven,'-TestPattern',($Plan.backend -join ',')) $root
        }
        if ($Plan.frontend.Count -gt 0) {
            $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
            $arguments = @('run','test','--')
            if ($Plan.frontend -notcontains '*') { $arguments += $Plan.frontend }
            Run-Step 'frontend' $npm $arguments (Join-Path $root 'frontend')
            if ($Plan.buildFrontend) { Run-Step 'frontend-build' $npm @('run','build') (Join-Path $root 'frontend') }
        }
        if ($Plan.ai.Count -gt 0) { Run-Step 'ai' $Python (@('-m','pytest') + $Plan.ai + @('-q')) (Join-Path $root 'ai-service') }
        if ($report.steps.Count -eq 0) { $report.status='no-automated-tests-selected' }
        else { $report.status='selected-checks-passed' }
    } catch { $report.status='failed'; $report.error=$_.Exception.Message; throw }
    finally {
        $report.finished=(Get-Date).ToUniversalTime().ToString('o')
        $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $logDirectory 'result.json') -Encoding UTF8
        Write-Host "Test evidence: $logDirectory"
        foreach ($gap in $Plan.missingCoverage) { Write-Warning $gap }
        Write-Host 'Automated results do not include the manual/browser checks listed in plan.json.'
    }
}
