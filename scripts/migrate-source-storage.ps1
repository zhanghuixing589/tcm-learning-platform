param([switch]$Apply,[string]$DatabaseBackup,[string]$Maven='D:\develop\apache-maven-3.9.9\bin\mvn.cmd',[string]$JavaHome='D:\develop\Java\jdk-17')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Import-DevEnvironment.ps1')
$env:JAVA_HOME=$JavaHome
$classpathFile=Join-Path $projectRoot 'runtime\storage-migration-classpath.txt'
Push-Location (Join-Path $projectRoot 'backend')
try {
    & $Maven -B -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Migration tool compilation failed' }
} finally { Pop-Location }
$classpath=(Join-Path $projectRoot 'backend\target\classes')+[IO.Path]::PathSeparator+(Get-Content -LiteralPath $classpathFile -Raw).Trim()
$arguments=@()
if ($Apply) {
    if (!$DatabaseBackup) { throw 'Apply requires -DatabaseBackup pointing to a current full database dump' }
    $arguments=@('--apply','--database-backup',(Resolve-Path -LiteralPath $DatabaseBackup).Path)
}
Push-Location $projectRoot
try {
    & (Join-Path $JavaHome 'bin\java.exe') -cp $classpath cn.tcm.platform.source.SourceStorageMigration @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Storage migration failed; retain all snapshots and original files' }
} finally { Pop-Location }
