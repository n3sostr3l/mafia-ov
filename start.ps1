$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot '.runtime'
$toolsDirectory = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $runtimeDirectory,$toolsDirectory -Force | Out-Null
function Is-RecentJava([string]$candidate) {
 if (-not (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) { return $false }
 $versionText = (& (Join-Path $candidate 'bin\java.exe') -version 2>&1 | Out-String)
 return $versionText -match 'version "(2[1-9]|[3-9][0-9])\.'
}
$javaCandidates = @($env:JAVA_HOME)
$javaCandidates += Get-ChildItem -Path 'C:\Program Files\Microsoft','C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
$javaCandidates += Get-ChildItem -Path 'C:\Program Files\JetBrains' -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'jbr' }
$selectedJava = $javaCandidates | Where-Object { $_ -and (Is-RecentJava $_) } | Select-Object -First 1
if (-not $selectedJava) { throw 'Install a JDK 21+ and set JAVA_HOME. Or run docker compose up --build.' }
$env:JAVA_HOME = $selectedJava
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Install Node.js 24 LTS.' }
$mavenPath = Join-Path $toolsDirectory 'apache-maven-3.9.11\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mavenPath)) {
 $archive = Join-Path $toolsDirectory 'maven.zip'
 $source = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
 Write-Host 'Downloading Apache Maven 3.9.11...'
 Invoke-WebRequest -Uri $source -OutFile $archive
 $expected = (Invoke-WebRequest -Uri ($source + '.sha512')).Content.Trim().Split(' ')[0]
 if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ne $expected) { throw 'Maven checksum mismatch.' }
 Expand-Archive -LiteralPath $archive -DestinationPath $toolsDirectory -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'frontend\node_modules\@angular\cli\bin\ng.js'))) {
 Push-Location (Join-Path $projectRoot 'frontend')
 try { & npm.cmd ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' } } finally { Pop-Location }
}
foreach ($port in @(8080,4200)) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is already in use. If Studio is running, open http://127.0.0.1:4200/demo. Otherwise free the port." } }
$backend = Start-Process -FilePath 'pwsh.exe' -ArgumentList @('-NoProfile','-File',('"' + (Join-Path $projectRoot 'scripts\backend-dev.ps1') + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory 'backend.log') -RedirectStandardError (Join-Path $runtimeDirectory 'backend-error.log') -PassThru
$frontend = Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"' + (Join-Path $projectRoot 'frontend\node_modules\@angular\cli\bin\ng.js') + '"'),'serve','--host','127.0.0.1','--port','4200') -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory 'frontend.log') -RedirectStandardError (Join-Path $runtimeDirectory 'frontend-error.log') -PassThru
@{backend=$backend.Id;frontend=$frontend.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeDirectory 'pids.json')
Write-Host 'Starting Polemica Studio. Open http://127.0.0.1:4200/demo in a few seconds.'
Write-Host 'Logs: .runtime/backend.log and .runtime/frontend.log. Stop: ./stop.ps1'
