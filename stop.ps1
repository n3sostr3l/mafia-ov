$ErrorActionPreference = 'Stop'
$pidPath = Join-Path $PSScriptRoot '.runtime\pids.json'
if (-not (Test-Path -LiteralPath $pidPath)) { Write-Host 'No recorded Studio processes.'; exit }
$recorded = Get-Content -LiteralPath $pidPath -Raw | ConvertFrom-Json
foreach ($processId in @($recorded.backend,$recorded.frontend)) {
 $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
 if ($process -and $process.CommandLine -match [regex]::Escape($PSScriptRoot)) { & taskkill.exe /PID $processId /T /F | Out-Null }
}
Remove-Item -LiteralPath $pidPath
Write-Host 'Studio stopped.'
