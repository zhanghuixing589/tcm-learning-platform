# Single source of test selection; no services are started while computing plans.
Set-StrictMode -Version Latest
function New-TestPlan {
    param([string[]]$Modules, [string[]]$Layers = @('backend','frontend','ai'))
    $known = @('all','identity','account','source','content','knowledge','operations','common','community','qa','learning','recommendation')
    foreach ($item in $Modules) { if ($item -notin $known) { throw "Unknown module: $item" } }
    foreach ($item in $Layers) { if ($item -notin @('backend','frontend','ai')) { throw "Unknown layer: $item" } }
    $global = @($Modules | Where-Object { $_ -in @('all','common','identity','operations') }).Count -gt 0
    $future = @($Modules | Where-Object { $_ -in @('community','qa','learning','recommendation') })
    $chain = @($Modules | Where-Object { $_ -in @('account','source','content','knowledge') }).Count -gt 0
    # Shared SQL resources, publication validity, evidence, review and personal lists
    # form one regression cluster even without Java imports between all callers.
    $backend = @(); $front = @(); $ai = @()
    if ('backend' -in $Layers) {
        if ($global -or $future.Count -gt 0) { $backend = @('*') }
        elseif ($chain) { $backend = @('IdentityIntegrationTest','PersonalCenterIntegrationTest','SourceWorkflowIntegrationTest','KnowledgeWorkflowIntegrationTest','KnowledgeTaxonomyIntegrationTest','KnowledgeTaxonomyMigrationTest') }
    }
    if ('frontend' -in $Layers) {
        if ($global -or $future.Count -gt 0) { $front = @('*') }
        elseif ($chain) { $front = @('src/modules/account','src/modules/knowledge','src/shared/api/client.test.ts','src/router/access.test.ts') }
    }
    if ('ai' -in $Layers -and ($global -or @($Modules | Where-Object { $_ -in @('source','content','knowledge','qa') }).Count -gt 0 -or $future.Count -gt 0)) { $ai = @('tests') }
    [pscustomobject][ordered]@{
        modules = @($Modules | Sort-Object -Unique); layers = @($Layers | Sort-Object -Unique)
        backend = $backend; frontend = $front; ai = $ai; buildFrontend = ($front.Count -gt 0)
        workflow = $false; paths = @(); reasons = @('Selected layers are explicit; inspect consumers before narrowing. Shared-resource modules expand as one regression cluster.')
        manual = @('Review changed behavior and consumers. For auth/session, routing, upload or publication changes run affected real-browser scenarios; record any unverified coverage.')
        missingCoverage = @($future | ForEach-Object { "$_ has no implemented dedicated suite; baseline regressions do not verify its proposed features." })
    }
}

function Get-ChangedTestPlan {
    param([string[]]$Paths)
    $modules = @(); $layers = @(); $reasons = @(); $workflow = $false
    foreach ($raw in $Paths) {
        $path = $raw.Replace('\','/')
        if ($path -match '^contracts/') { $modules += 'all'; $layers += @('backend','frontend','ai'); $reasons += "Contract: $path" }
        elseif ($path -match '^scripts/(Test-Policy|Invoke-TestPlan|test-module|test-changed|check-env)\.ps1$' -or $path -eq 'scripts/tests/workflow.Tests.ps1') { $workflow = $true }
        elseif ($path -match '^(AGENTS|MODULE_INDEX|DEVELOPMENT_STATE|DECISIONS|README|PRODUCT|DESIGN|task_plan|findings|progress)\.md$' -or $path -match '^(docs/|\.agents/skills/).+\.md$') { }
        elseif ($path -match '^backend/src/test/') { $modules += 'all'; $layers += 'backend'; $reasons += "Test source changed; include newly added or moved classes: $path" }
        elseif ($path -match '^backend/src/main/java/cn/tcm/platform/([^/]+)/') {
            $module = $Matches[1]
            if ($module -in @('identity','common','operations')) { $modules += 'all'; $layers += @('backend','frontend','ai') }
            elseif ($module -in @('account','source','content','knowledge','community','qa','learning','recommendation')) {
                $modules += $module; $layers += @('backend','frontend')
                if ($module -in @('source','content','knowledge','qa')) { $layers += 'ai' }
            } else { $modules += 'all'; $layers += @('backend','frontend','ai') }
        }
        elseif ($path -match '^frontend/src/(shared/(api|stores)/|router/)' -or $path -match '^frontend/src/modules/account/(AuthView\.vue|passwordPolicy\.(ts|test\.ts))$') {
            $modules += 'identity'; $layers += @('backend','frontend'); $reasons += "Client authentication/routing boundary: $path"
        }
        elseif ($path -match '^frontend/src/modules/(account|knowledge|admin)/') {
            $module = $Matches[1]
            if ($module -eq 'admin') { $module = 'identity' }
            $modules += $module; $layers += 'frontend'
        }
        elseif ($path -match '^frontend/') { $modules += 'all'; $layers += 'frontend' }
        elseif ($path -match '^ai-service/') { $modules += 'all'; $layers += @('ai','backend','frontend') }
        else { $modules += 'all'; $layers += @('backend','frontend','ai'); $reasons += "Unmapped/shared configuration or schema: $path" }
    }
    $plan = New-TestPlan -Modules @($modules | Sort-Object -Unique) -Layers @($layers | Sort-Object -Unique)
    $plan.paths = @($Paths | Sort-Object -Unique); $plan.workflow = $workflow
    $plan.reasons = @($reasons)
    if ($modules.Count -eq 0) { $plan.reasons += 'Documentation/workflow-only change: no business suite selected; validate links, claims and workflow tests.' }
    else { $plan.reasons += 'Expanded by shared tables, publication/evidence rules and consumers; inspect diff for additional dynamic dependencies.' }
    return $plan
}

function Get-GitChangedPaths {
    param([string]$Base)
    $commands = @()
    if ($Base) {
        & git rev-parse --verify "$Base^{commit}" 1>$null 2>$null
        if ($LASTEXITCODE -ne 0) { throw "Invalid Git base: $Base" }
        $mergeBase = & git merge-base HEAD $Base
        if ($LASTEXITCODE -ne 0) { throw 'Cannot determine merge base' }
        $commands += ,@('diff','--name-only','--no-renames','-z',"$mergeBase",'HEAD','--')
    }
    $commands += ,@('diff','--name-only','--no-renames','-z','HEAD','--')
    # Separate cached/unstaged diffs also catch staged edits undone in the worktree.
    $commands += ,@('diff','--cached','--name-only','--no-renames','-z','--')
    $commands += ,@('diff','--name-only','--no-renames','-z','--')
    $commands += ,@('ls-files','--others','--exclude-standard','-z','--')
    $paths = @()
    foreach ($arguments in $commands) {
        $output = @(& git -c core.quotepath=false @arguments)
        if ($LASTEXITCODE -ne 0) { throw 'Git change discovery failed; no test result claimed.' }
        $paths += ($output -join "`n").Split([char]0, [System.StringSplitOptions]::RemoveEmptyEntries)
    }
    @($paths | Sort-Object -Unique)
}
