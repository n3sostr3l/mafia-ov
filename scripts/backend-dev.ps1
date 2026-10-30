$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $projectRoot 'backend')
& (Join-Path $projectRoot '.tools\apache-maven-3.9.11\bin\mvn.cmd') -B -q spring-boot:run '-Dspring-boot.run.profiles=dev'
exit $LASTEXITCODE
