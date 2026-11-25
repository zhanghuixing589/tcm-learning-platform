[CmdletBinding()]
param(
    [string]$Base,
    [switch]$PlanOnly,
    [string]$JavaHome = 'D:\develop\Java\jdk-17',
    [string]$Maven = 'D:\develop\apache-maven-3.9.9\bin\mvn.cmd',
    [string]$Python = (Join-Path $PSScriptRoot '../ai-service/.venv/Scripts/python.exe')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Test-Policy.ps1')
. (Join-Path $PSScriptRoot 'Invoke-TestPlan.ps1')
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $paths = @(Get-GitChangedPaths -Base $Base)
    $plan = Get-ChangedTestPlan -Paths $paths
    Invoke-TestPlan -Plan $plan -PlanOnly:$PlanOnly -JavaHome $JavaHome -Maven $Maven -Python $Python
} finally { Pop-Location }
