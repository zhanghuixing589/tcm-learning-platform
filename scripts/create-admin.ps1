param([string]$Username,[string]$JavaHome='D:\develop\Java\jdk-17')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Import-DevEnvironment.ps1')
if (!$Username) { $Username=Read-Host 'New administrator username' }
$securePassword=Read-Host 'Password (at least 10 characters)' -AsSecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:BOOTSTRAP_ADMIN_USERNAME=$Username
    $env:BOOTSTRAP_ADMIN_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    & (Join-Path $JavaHome 'bin\java.exe') '-Duser.timezone=UTC' '-jar' (Join-Path $projectRoot 'backend\target\tcm-platform-0.1.0.jar') '--bootstrap-admin' '--server.port=0'
    if ($LASTEXITCODE -ne 0) { throw 'Administrator initialization failed' }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    Remove-Item Env:\BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\BOOTSTRAP_ADMIN_USERNAME -ErrorAction SilentlyContinue
}
