[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('all','identity','account','source','content','knowledge','operations','common','community','qa','learning','recommendation')][string[]]$Module,
    [ValidateSet('backend','frontend','ai')][string[]]$Layer = @('backend','frontend','ai'),
    [switch]$PlanOnly,
    [string]$JavaHome = 'D:\develop\Java\jdk-17',
    [string]$Maven = 'D:\develop\apache-maven-3.9.9\bin\mvn.cmd',
    [string]$Python = (Join-Path $PSScriptRoot '../ai-service/.venv/Scripts/python.exe')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Test-Policy.ps1')
. (Join-Path $PSScriptRoot 'Invoke-TestPlan.ps1')
$plan = New-TestPlan -Modules $Module -Layers $Layer
Invoke-TestPlan -Plan $plan -PlanOnly:$PlanOnly -JavaHome $JavaHome -Maven $Maven -Python $Python
