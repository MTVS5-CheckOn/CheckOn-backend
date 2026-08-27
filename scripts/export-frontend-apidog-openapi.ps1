[CmdletBinding()]
param(
	[string]$OutputDirectory = 'build/apidog/frontend'
)

$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $backendRoot $OutputDirectory))
$previousOutput = [Environment]::GetEnvironmentVariable('CHECKON_APIDOG_OUTPUT', 'Process')

Push-Location $backendRoot
try {
	[Environment]::SetEnvironmentVariable('CHECKON_APIDOG_OUTPUT', $resolvedOutput, 'Process')
	& .\gradlew.bat test `
		--tests com.checkon.global.openapi.FrontendApidogExportContractTest `
		--no-daemon
	if ($LASTEXITCODE -ne 0) {
		throw '프론트 Apidog OpenAPI export 검증에 실패했습니다.'
	}
}
finally {
	[Environment]::SetEnvironmentVariable('CHECKON_APIDOG_OUTPUT', $previousOutput, 'Process')
	Pop-Location
}

Write-Host '프론트 OpenAPI 58개 operation을 중복 없이 분할했습니다.'
Write-Host "출력 경로: $resolvedOutput"
Write-Host '포함 검증: 신규 학부모/상담 조회, Guardian Labels, Problem Studio revision'
