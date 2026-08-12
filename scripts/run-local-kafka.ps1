[CmdletBinding()]
param(
    [string]$EnvironmentFile,
    [switch]$PrepareDemoData,
    [string]$DemoAnalysisDate = (Get-Date -Format 'yyyy-MM-dd')
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
# The standalone checkon-kafka-adapter owns requested-topic consumption.
# Keeping the embedded fallback off prevents duplicate completed/failed events.
$env:RISK_DETECTION_HTTP_ADAPTER_ENABLED = 'false'

if ($PrepareDemoData) {
    # This applies only to the child process launched by this script. It makes
    # the demo's fixed teacher principal match the idempotent local fixture.
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:TEST_AUTH_ENABLED = 'true'
    $env:TEST_ACCOUNT_ID = '0198f000-0000-7000-8000-000000000000'
    $env:TEST_TEACHER_PROFILE_ID = '0198f000-0000-7000-8000-000000000001'
}

Push-Location $projectRoot
try {
	Write-Host 'PostgreSQL과 Kafka를 시작합니다.'
	docker compose up -d postgres kafka
	if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 시작에 실패했습니다.' }

	if ($PrepareDemoData) {
		& "$PSScriptRoot\seed-risk-detection-demo-data.ps1" `
			-AnalysisDate $DemoAnalysisDate
		if ($LASTEXITCODE -ne 0) { throw 'Kafka 시연용 학습 데이터 준비에 실패했습니다.' }
		Write-Host "시연 데이터 준비 완료. Apidog에 analysisDate=$DemoAnalysisDate 를 사용하세요."
		Write-Host 'dev 테스트 인증이 활성화되어 있으므로 Apidog Authorization 헤더는 비워 둡니다.'
	}

	# Gradle daemon은 과거 실행의 환경 변수를 보관할 수 있다. 새 프로세스로
	# 실행해 .env와 위 호환 변수를 확실히 적용한다.
	& .\gradlew.bat --stop
	& .\gradlew.bat --no-daemon bootRun
}
finally {
	Pop-Location
}
