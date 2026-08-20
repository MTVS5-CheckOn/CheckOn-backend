[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$stateFile = Join-Path $backendRoot 'build\local-problem-generation-e2e\processes.json'

if (-not (Test-Path -LiteralPath $stateFile)) {
	Write-Host '실행 중인 로컬 문제 출제 환경 상태 파일이 없습니다.'
	return
}

$processes = @(Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json)
foreach ($entry in $processes) {
	$process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
	if ($null -ne $process) {
		$expectedStartedAt = if ($null -ne $entry.startedAtUtc) {
			[DateTime]::Parse($entry.startedAtUtc).ToUniversalTime()
		}
		else { $null }
		$actualStartedAt = $process.StartTime.ToUniversalTime()
		$startTimeMatches = $null -ne $expectedStartedAt -and `
			[Math]::Abs(($actualStartedAt - $expectedStartedAt).TotalSeconds) -lt 1
		$nameMatches = $null -ne $entry.processName -and `
			$process.ProcessName -eq $entry.processName
		if (-not ($startTimeMatches -and $nameMatches)) {
			Write-Warning "PID가 다른 프로세스에 재사용되었을 수 있어 종료하지 않습니다. PID=$($entry.pid)"
			continue
		}
		& taskkill.exe /PID $entry.pid /T /F | Out-Null
		Write-Host "$($entry.name) 프로세스를 종료했습니다. PID=$($entry.pid)"
	}
}
Remove-Item -LiteralPath $stateFile
Write-Host 'PostgreSQL과 Kafka 데이터는 재사용할 수 있도록 실행 상태를 유지합니다.'
