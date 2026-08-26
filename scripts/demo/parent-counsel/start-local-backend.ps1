[CmdletBinding()]
param(
	[string]$EnvironmentFile,
	[int]$Port = 18080,
	[switch]$EnableTestAuthentication,
	[string]$AllowedOrigins
)

$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$envFile = if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
	Join-Path $backendRoot '.env'
}
else {
	(Resolve-Path $EnvironmentFile).Path
}

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
	throw "환경 파일이 없습니다: $envFile"
}
if ($Port -lt 1 -or $Port -gt 65535) {
	throw 'Port는 1~65535 범위여야 합니다.'
}

Get-Content -LiteralPath $envFile | ForEach-Object {
	if ($_ -match '^\s*([^#=\s]+)\s*=(.*)$') {
		[Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
	}
}

$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:SERVER_PORT = $Port.ToString()
if ($EnableTestAuthentication) {
	$env:TEST_AUTH_ENABLED = 'true'
}
if (-not [string]::IsNullOrWhiteSpace($AllowedOrigins)) {
	$env:AUTH_ALLOWED_ORIGINS = $AllowedOrigins
}

Push-Location $backendRoot
try {
	Write-Host "CheckOn 로컬 Backend를 http://localhost:$Port 에서 시작합니다."
	& .\gradlew.bat bootRun --no-daemon
	if ($LASTEXITCODE -ne 0) {
		throw 'CheckOn 로컬 Backend 실행에 실패했습니다.'
	}
}
finally {
	Pop-Location
}
