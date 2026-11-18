param([string]$JavaHome='D:\develop\Java\jdk-17')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Import-DevEnvironment.ps1')
$name='e2e_admin_'+(Get-Date -Format 'yyyyMMddHHmmss')
$randomBytes=New-Object byte[] 8
$random=[Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
$password='Aa'+[Convert]::ToBase64String($randomBytes).Substring(0,8)+'1!'
try {
    $env:BOOTSTRAP_ADMIN_USERNAME=$name; $env:BOOTSTRAP_ADMIN_PASSWORD=$password
    & (Join-Path $JavaHome 'bin\java.exe') '-Duser.timezone=UTC' '-jar' (Join-Path $projectRoot 'backend\target\tcm-platform-0.1.0.jar') '--bootstrap-admin' '--server.port=0' *> (Join-Path $projectRoot 'runtime\bootstrap-admin.log')
    if ($LASTEXITCODE -ne 0) { throw 'E2E administrator bootstrap failed; see runtime/bootstrap-admin.log' }
    @{username=$name;password=$password} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectRoot 'runtime\e2e-admin.json') -Encoding UTF8
    Write-Host 'Created an isolated E2E administrator. Credentials are stored only under ignored runtime/.'
} finally {
    Remove-Item Env:\BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\BOOTSTRAP_ADMIN_USERNAME -ErrorAction SilentlyContinue
}
