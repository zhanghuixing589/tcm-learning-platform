# No Pester dependency; uses only isolated Git fixtures and fake runner commands.
$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot 'Test-Policy.ps1')
. (Join-Path $scriptsRoot 'Invoke-TestPlan.ps1')
$script:assertions = 0
function Assert([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw "Assertion failed: $Message" }
    $script:assertions++
}
function Invoke-FixtureGit([string[]]$Arguments) { & git @Arguments 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw "Fixture git failed: $Arguments" } }
$plan = Get-ChangedTestPlan @('docs/modules/knowledge.md','README.md','.agents/skills/project-context/SKILL.md')
Assert ($plan.backend.Count -eq 0 -and $plan.frontend.Count -eq 0 -and $plan.ai.Count -eq 0) 'docs do not launch business tests'
$plan = Get-ChangedTestPlan @('backend/src/main/java/cn/tcm/platform/knowledge/application/KnowledgeService.java')
Assert ($plan.backend -contains 'PersonalCenterIntegrationTest' -and $plan.backend -contains 'SourceWorkflowIntegrationTest') 'shared-resource consumers expanded'
Assert ($plan.frontend -contains 'src/modules/account' -and $plan.ai.Count -gt 0) 'cross-layer consumers included'
  Assert ($plan.backend -contains 'KnowledgeTaxonomyIntegrationTest' -and $plan.backend -contains 'KnowledgeTaxonomyMigrationTest') 'typed taxonomy and compatibility migration stay in the shared regression cluster'
foreach ($path in @('backend/src/main/java/cn/tcm/platform/common/PublishedKnowledge.java','backend/src/main/java/cn/tcm/platform/identity/infrastructure/SecurityConfiguration.java','backend/src/main/resources/db/migration/V7__example.sql','contracts/README.md','.env.example','deploy/nginx.conf','scripts/dev.ps1','unknown/new.bin','backend/pom.xml')) {
    $plan = Get-ChangedTestPlan @($path)
    Assert ($plan.backend -contains '*' -and $plan.frontend -contains '*' -and $plan.ai.Count -gt 0) "conservative fallback: $path"
}
$plan = Get-ChangedTestPlan @('frontend/src/shared/api/client.ts','frontend/package-lock.json')
Assert ($plan.frontend -contains '*' -and $plan.buildFrontend) 'frontend common/lock includes tests and type/build'
Assert ($plan.backend -contains '*') 'client session/auth changes also exercise server security boundaries'
$plan = Get-ChangedTestPlan @('ai-service/app/documents.py')
Assert ($plan.ai.Count -gt 0 -and $plan.backend -contains '*') 'Python contract consumers'
$plan = Get-ChangedTestPlan @('backend/src/test/java/cn/tcm/platform/source/NewSecurityTest.java')
Assert ($plan.backend -contains '*') 'new nested tests cannot fall outside the fixed existing-class cluster'
$plan = Get-ChangedTestPlan @('scripts/test-module.ps1')
Assert ($plan.workflow -and $plan.backend.Count -eq 0) 'workflow self checks only'
$plan = New-TestPlan @('community')
Assert ($plan.missingCoverage.Count -eq 1 -and $plan.backend -contains '*') 'planned module cannot masquerade as covered'
$failed=$false
try { New-TestPlan @('typo') | Out-Null } catch { $failed=$true }
Assert $failed 'unknown module rejected'

$fixture=Join-Path (Split-Path -Parent $scriptsRoot) ('runtime/workflow-fixture-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
Push-Location $fixture
try {
    Invoke-FixtureGit @('init','-q')
    foreach ($file in @('stage.txt','delete.txt','rename old 中文.txt')) { Set-Content -LiteralPath $file -Value 'initial' }
    Invoke-FixtureGit @('add','--','.')
    Invoke-FixtureGit @('-c','user.name=Workflow Test','-c','user.email=workflow@example.invalid','commit','-qm','fixture base')
    $base=(& git rev-parse HEAD)
    Assert (@(Get-GitChangedPaths).Count -eq 0) 'clean worktree'
    Set-Content stage.txt 'staged'
    Invoke-FixtureGit @('add','--','stage.txt')
    Set-Content stage.txt 'initial' # net worktree matches HEAD, index still changed
    Remove-Item -LiteralPath (Join-Path $fixture 'delete.txt') # one explicitly known fixture file
    Invoke-FixtureGit @('mv','--','rename old 中文.txt','rename new 中文.txt')
    Set-Content -LiteralPath 'untracked space 中文.txt' -Value 'new'
    $paths=@(Get-GitChangedPaths)
    foreach ($expected in @('stage.txt','delete.txt','rename old 中文.txt','rename new 中文.txt','untracked space 中文.txt')) {
        Assert ($paths -contains $expected) "Git detects $expected"
    }
    Invoke-FixtureGit @('add','--','.')
    Invoke-FixtureGit @('-c','user.name=Workflow Test','-c','user.email=workflow@example.invalid','commit','-qm','fixture change')
    Assert (@(Get-GitChangedPaths -Base $base) -contains 'rename old 中文.txt') 'base includes committed deletion side of rename'
    $failed=$false
    try { Get-GitChangedPaths -Base 'nonexistent-workflow-ref' | Out-Null } catch { $failed=$true }
    Assert $failed 'invalid base fails closed'
} finally { Pop-Location }

function Invoke-WorkflowFailure { $global:LASTEXITCODE=23; Write-Output 'expected fixture failure' }
function Invoke-WorkflowSuccess { $global:LASTEXITCODE=0; Write-Output 'expected fixture success' }
$plan=New-TestPlan @('source') @('ai')
$plan.reasons=@('Self-test fixture only: fake commands are not business test evidence.')
$fixtureResults=Join-Path $fixture 'runner-results'
$json=Invoke-TestPlan -Plan $plan -PlanOnly
Assert (($json | ConvertFrom-Json).ai -contains 'tests') 'preview returns JSON without executing'
$failed=$false
try { Invoke-TestPlan -Plan $plan -Python 'Invoke-WorkflowFailure' -EvidenceRoot $fixtureResults } catch { $failed=$true }
Assert $failed 'runner propagates nonzero native exit'
Invoke-TestPlan -Plan $plan -Python 'Invoke-WorkflowSuccess' -EvidenceRoot $fixtureResults
$results=@(Get-ChildItem -LiteralPath $fixtureResults -Filter result.json -Recurse | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })
Assert (@($results | Where-Object status -eq 'failed').Count -eq 1) 'failed result persisted'
Assert (@($results | Where-Object status -eq 'selected-checks-passed').Count -eq 1) 'success result persisted'
Write-Host "Workflow checks passed: $script:assertions assertions. Fixtures: $fixture"

