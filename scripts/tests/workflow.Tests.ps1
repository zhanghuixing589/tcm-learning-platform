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
Assert ($plan.backend -contains 'SourceReadingIntegrationTest' -and $plan.backend -contains 'KnowledgeProposalIntegrationTest' -and $plan.backend -contains 'SourceProcessingIntegrationTest') 'immutable reading, independent proposals and lease processing stay in shared regression cluster'
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
foreach ($path in @('scripts/dev.ps1','scripts/Dev-Ports.ps1','scripts/Import-DevEnvironment.ps1')) {
    $plan=Get-ChangedTestPlan @($path)
    Assert ($plan.workflow -and $plan.backend -contains '*' -and $plan.frontend -contains '*' -and $plan.ai.Count -gt 0) 'shared environment retains full regression and port checks'
}
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
. (Join-Path $scriptsRoot 'Dev-Ports.ps1')
$portFixture=Join-Path $fixture 'ports'
New-Item -ItemType Directory -Path (Join-Path $portFixture 'runtime') -Force | Out-Null
$startupLock=Enter-DevStartLock -ProjectRoot $portFixture
try {
    $failed=$false
    try { $unexpectedLock=Enter-DevStartLock -ProjectRoot $portFixture; $unexpectedLock.Dispose() } catch { $failed=$true }
    Assert $failed 'concurrent launch in the same worktree is rejected'
} finally { $startupLock.Dispose() }
$startupLock=Enter-DevStartLock -ProjectRoot $portFixture
$startupLock.Dispose()
Assert $true 'startup lock releases after launcher ends'
$ports=Get-DevPorts -ProjectRoot $portFixture
Assert ($ports.FrontendPort -eq 5177 -and $ports.BackendPort -eq 8084 -and $ports.AiPort -eq 8004) 'stable main defaults'
Set-Content -LiteralPath (Join-Path $portFixture '.git') -Value 'gitdir: fixture'
$failed=$false
try { Get-DevPorts -ProjectRoot $portFixture } catch { $failed=$true }
Assert $failed 'linked worktree cannot silently reuse main ports'
$ports=Get-DevPorts -ProjectRoot $portFixture -Overrides @{FrontendPort=15178;BackendPort=18085;AiPort=18005}
Assert ($ports.BackendPort -eq 18085) 'explicit complete worktree ports supported'
$profilePath=Join-Path $portFixture 'runtime/dev-ports.json'
@{FrontendPort=15178;BackendPort=18085;AiPort=18005} | ConvertTo-Json | Set-Content $profilePath
$ports=Get-DevPorts -ProjectRoot $portFixture -Overrides @{AiPort=18006}
Assert ($ports.FrontendPort -eq 15178 -and $ports.AiPort -eq 18006) 'local profile and explicit override precedence'
foreach ($badProfile in @(@{FrontendPort=5177;BackendPort=5177;AiPort=8004},@{FrontendPort=70000;BackendPort=8084;AiPort=8004},@{FrontendPort=5177},@{FrontendPort=5177.5;BackendPort=8084;AiPort=8004})) {
    $badProfile | ConvertTo-Json | Set-Content $profilePath
    $failed=$false
    try { Get-DevPorts -ProjectRoot $portFixture } catch { $failed=$true }
    Assert $failed 'invalid/incomplete/duplicate port profiles fail closed'
}
$busyListener=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
try {
    $busyListener.Start()
    $busyPort=$busyListener.LocalEndpoint.Port
    $failed=$false
    try { Assert-DevPortAvailable -Port $busyPort -Service 'fixture' } catch { $failed=$_.Exception.Message -like "*fixture*127.0.0.1:$busyPort*" }
    Assert $failed 'busy port reports service and endpoint'
} finally { $busyListener.Stop() }
Assert-DevPortAvailable -Port $busyPort -Service 'fixture'
Assert $true 'released port is available'
$environmentFixture=Join-Path $fixture 'environment'
New-Item -ItemType Directory -Path (Join-Path $environmentFixture 'scripts'),(Join-Path $environmentFixture 'runtime') -Force | Out-Null
foreach ($file in @('Dev-Ports.ps1','Import-DevEnvironment.ps1')) { Copy-Item -LiteralPath (Join-Path $scriptsRoot $file) -Destination (Join-Path $environmentFixture "scripts/$file") }
@{port=12345;password='fixture-only'} | ConvertTo-Json | Set-Content (Join-Path $environmentFixture 'runtime/dev-mysql.json')
@{jwt='fixture-only';internal='fixture-only'} | ConvertTo-Json | Set-Content (Join-Path $environmentFixture 'runtime/dev-secrets.json')
$env:SERVER_ADDRESS='::'
& {
    . (Join-Path $environmentFixture 'scripts/Import-DevEnvironment.ps1') -BackendPort 18085 -FrontendPort 15178 -AiPort 18005
    Assert ($env:SERVER_ADDRESS -eq '127.0.0.1' -and $env:SERVER_PORT -eq '18085') 'environment pins IPv4 loopback and selected backend'
    Assert ($env:BACKEND_URL -eq 'http://127.0.0.1:18085' -and $env:AI_BACKEND_URL -eq $env:BACKEND_URL) 'frontend proxy and AI callback use same backend'
    Assert ($env:AI_BASE_URL -eq 'http://127.0.0.1:18005' -and $env:ALLOWED_ORIGINS -eq 'http://localhost:15178,http://127.0.0.1:15178') 'AI endpoint and allowed origins follow selected profile'
}
Write-Host "Workflow checks passed: $script:assertions assertions. Fixtures: $fixture"

