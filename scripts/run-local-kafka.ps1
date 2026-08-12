[CmdletBinding()]
param(
	[string]$EnvironmentFile
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = if ($EnvironmentFile) {
	$EnvironmentFile
}
else {
	Join-Path $projectRoot '.env'
}

if (-not (Test-Path -LiteralPath $envFile)) {
	throw ".env 파일이 없습니다. .env.example을 참고해 DB 환경 변수를 먼저 설정하세요."
}

Get-Content -LiteralPath $envFile | ForEach-Object {
	if ($_ -match '^\s*([^#=\s]+)\s*=(.*)$') {
		[Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
	}
}

function Test-UnusableEnvironmentValue([string]$value) {
	return [string]::IsNullOrWhiteSpace($value) -or $value -match '^\$\{.+\}$'
}

function Require-EnvironmentVariable([string]$name) {
	$value = [Environment]::GetEnvironmentVariable($name, 'Process')
	if ([string]::IsNullOrWhiteSpace($value)) {
		throw "$name 환경 변수가 비어 있습니다. .env를 확인하세요."
	}
	if ($value -match '^\$\{.+\}$') {
		throw "$name 값에 $value 같은 참조 문자열을 넣을 수 없습니다. 실제 값을 설정하세요."
	}
	return $value
}

# 기존 .env의 FLYWAY_DB_USERNAME과 현재 Spring 설정의 FLYWAY_DB_USER를
# 호환한다. URL은 application.yaml에서 DB_URL을 fallback으로 사용한다.
if (Test-UnusableEnvironmentValue $env:FLYWAY_DB_USER) {
	if (-not [string]::IsNullOrWhiteSpace($env:FLYWAY_DB_USERNAME)) {
		$env:FLYWAY_DB_USER = $env:FLYWAY_DB_USERNAME
	}
	else {
		$env:FLYWAY_DB_USER = $env:POSTGRES_USER
	}
}
if (Test-UnusableEnvironmentValue $env:FLYWAY_DB_PASSWORD) {
	$env:FLYWAY_DB_PASSWORD = $env:POSTGRES_PASSWORD
}

$null = Require-EnvironmentVariable 'DB_URL'
$null = Require-EnvironmentVariable 'DB_USERNAME'
$null = Require-EnvironmentVariable 'DB_PASSWORD'
$null = Require-EnvironmentVariable 'POSTGRES_DB'
$null = Require-EnvironmentVariable 'POSTGRES_USER'
$null = Require-EnvironmentVariable 'POSTGRES_PASSWORD'
$null = Require-EnvironmentVariable 'CHECKON_APP_DB_USER'
$null = Require-EnvironmentVariable 'CHECKON_APP_DB_PASSWORD'
$null = Require-EnvironmentVariable 'FLYWAY_DB_USER'
$null = Require-EnvironmentVariable 'FLYWAY_DB_PASSWORD'

$env:SPRING_DOCKER_COMPOSE_ENABLED = 'false'
$env:KAFKA_BOOTSTRAP_SERVERS = if ($env:KAFKA_BOOTSTRAP_SERVERS) {
	$env:KAFKA_BOOTSTRAP_SERVERS
}
else {
	'localhost:9094'
}
$env:CHECKON_KAFKA_ENABLED = 'true'

Push-Location $projectRoot
try {
	Write-Host 'PostgreSQL과 Kafka를 시작합니다.'
	docker compose up -d postgres kafka
	if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 시작에 실패했습니다.' }

	# Gradle daemon은 과거 실행의 환경 변수를 보관할 수 있다. 새 프로세스로
	# 실행해 .env와 위 호환 변수를 확실히 적용한다.
	& .\gradlew.bat --stop
	& .\gradlew.bat --no-daemon bootRun
}
finally {
	Pop-Location
}
