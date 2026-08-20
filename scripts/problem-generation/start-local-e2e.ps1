[CmdletBinding()]
param(
	[Parameter(Mandatory)]
	[ValidatePattern('^https?://')]
	[string]$AiBaseUrl,
	[Parameter(Mandatory)]
	[ValidateNotNullOrEmpty()]
	[string]$AdapterRoot,
	[switch]$SkipSeed
)

$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adapterRootPath = (Resolve-Path $AdapterRoot).Path
$stateDirectory = Join-Path $backendRoot 'build\local-problem-generation-e2e'
$stateFile = Join-Path $stateDirectory 'processes.json'
$adapterDatabase = 'checkon_kafka_adapter'

if (-not (Test-Path -LiteralPath (Join-Path $adapterRootPath 'gradlew.bat') -PathType Leaf)) {
	throw "AdapterRoot에 gradlew.bat가 없습니다: $adapterRootPath"
}

function Import-EnvironmentFile([string]$path) {
	if (-not (Test-Path -LiteralPath $path)) { throw "환경 파일이 없습니다: $path" }
	Get-Content -LiteralPath $path | ForEach-Object {
		if ($_ -match '^\s*([^#=\s]+)\s*=(.*)$') {
			[Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
		}
	}
}

function Require-EnvironmentVariable([string]$name) {
	$value = [Environment]::GetEnvironmentVariable($name, 'Process')
	if ([string]::IsNullOrWhiteSpace($value) -or $value -match '^\$\{.+\}$') {
		throw "$name 환경 변수가 비어 있거나 실제 값이 아닙니다. Backend .env를 확인하세요."
	}
	return $value
}

function Wait-TcpPort([int]$port, [string]$name, [int]$timeoutSeconds = 120) {
	$deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
	do {
		$client = [System.Net.Sockets.TcpClient]::new()
		try {
			$task = $client.ConnectAsync('127.0.0.1', $port)
			if ($task.Wait(500) -and $client.Connected) {
				Write-Host "$name 준비 완료: localhost:$port"
				return
			}
		}
		catch { }
		finally { $client.Dispose() }
		Start-Sleep -Milliseconds 750
	} while ([DateTime]::UtcNow -lt $deadline)
	throw "$name 포트 localhost:$port가 ${timeoutSeconds}초 안에 열리지 않았습니다. 로그를 확인하세요."
}

function Assert-AdapterDiagnosisRoute([int]$timeoutSeconds = 30) {
	$uri = 'http://127.0.0.1:8081/internal/v1/problem-diagnoses'
	$deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
	do {
		try {
			$response = Invoke-WebRequest -UseBasicParsing -Method Post `
				-Uri $uri -ContentType 'application/json' -Body '{}' -TimeoutSec 5
			$statusCode = [int]$response.StatusCode
		}
		catch {
			$statusCode = if ($null -ne $_.Exception.Response) {
				[int]$_.Exception.Response.StatusCode
			}
			else { $null }
		}
		finally {
			if ($null -ne $response) { $response.Dispose() }
			$response = $null
		}

		if ($statusCode -eq 400) {
			Write-Host 'Adapter 진단 라우트 준비 완료: /internal/v1/problem-diagnoses'
			return
		}
		if ($statusCode -eq 404) {
			throw "현재 Adapter 빌드에 진단 라우트가 없습니다: $uri"
		}
		Start-Sleep -Milliseconds 750
	} while ([DateTime]::UtcNow -lt $deadline)
	throw "Adapter 진단 라우트를 확인할 수 없습니다: $uri"
}

function Start-GradleApplication([string]$root, [string]$name) {
	$stdout = Join-Path $stateDirectory "$name.out.log"
	$stderr = Join-Path $stateDirectory "$name.err.log"
	$process = Start-Process -FilePath (Join-Path $root 'gradlew.bat') `
		-ArgumentList '--no-daemon', 'bootRun' `
		-WorkingDirectory $root `
		-WindowStyle Hidden `
		-RedirectStandardOutput $stdout `
		-RedirectStandardError $stderr `
		-PassThru
	$process.Refresh()
	return @{
		name = $name
		pid = $process.Id
		processName = $process.ProcessName
		startedAtUtc = $process.StartTime.ToUniversalTime().ToString('o')
		root = $root
		stdout = $stdout
		stderr = $stderr
	}
}

if (Test-Path -LiteralPath $stateFile) {
	throw "이미 실행 상태 파일이 있습니다: $stateFile`n먼저 stop-local-e2e.ps1을 실행하세요."
}

Import-EnvironmentFile (Join-Path $backendRoot '.env')
$postgresDatabase = Require-EnvironmentVariable 'POSTGRES_DB'
$postgresUser = Require-EnvironmentVariable 'POSTGRES_USER'
$postgresPassword = Require-EnvironmentVariable 'POSTGRES_PASSWORD'
$appUser = Require-EnvironmentVariable 'CHECKON_APP_DB_USER'
$appPassword = Require-EnvironmentVariable 'CHECKON_APP_DB_PASSWORD'
$null = Require-EnvironmentVariable 'CHECKON_JWT_SECRET'
if ($appUser -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
	throw 'CHECKON_APP_DB_USER는 안전한 PostgreSQL role 이름이어야 합니다.'
}

$normalizedAiBaseUrl = $AiBaseUrl.TrimEnd('/')
try {
	$response = Invoke-WebRequest -UseBasicParsing -Method Get `
		-Uri "$normalizedAiBaseUrl/openapi.json" -TimeoutSec 10
	Write-Host "AI 서버 도달 확인: HTTP $([int]$response.StatusCode)"
}
catch {
	if ($null -ne $_.Exception.Response) {
		Write-Host "AI 서버 도달 확인: HTTP $([int]$_.Exception.Response.StatusCode)"
	}
	else {
	throw "AI 서버에 도달할 수 없습니다: $normalizedAiBaseUrl ($($_.Exception.Message))"
	}
}
finally {
	if ($null -ne $response) { $response.Dispose() }
}

Push-Location $backendRoot
try {
	Write-Host '로컬 PostgreSQL과 Kafka를 시작합니다.'
	docker compose up -d postgres kafka
	if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 시작에 실패했습니다.' }
	Wait-TcpPort 5432 'PostgreSQL'
	Wait-TcpPort 9094 'Kafka'

	$databaseSql = @"
SELECT 'CREATE DATABASE $adapterDatabase'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='$adapterDatabase')
\gexec
"@
	$databaseSql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U $postgresUser -d postgres
	if ($LASTEXITCODE -ne 0) { throw 'Adapter 전용 데이터베이스 준비에 실패했습니다.' }
	$grantSql = "GRANT CONNECT ON DATABASE $adapterDatabase TO $appUser; " +
		"GRANT USAGE ON SCHEMA public TO $appUser; " +
		"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $appUser; " +
		"ALTER DEFAULT PRIVILEGES IN SCHEMA public " +
		"GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $appUser;"
	$grantSql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U $postgresUser -d $adapterDatabase
	if ($LASTEXITCODE -ne 0) { throw 'Adapter DB 권한 준비에 실패했습니다.' }
}
finally { Pop-Location }

New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
$startedProcesses = @()

# Adapter process environment
$env:DB_URL = "jdbc:postgresql://localhost:5432/$adapterDatabase"
$env:DB_USERNAME = $appUser
$env:DB_PASSWORD = $appPassword
$env:FLYWAY_DB_URL = "jdbc:postgresql://localhost:5432/$adapterDatabase"
$env:FLYWAY_DB_USER = $postgresUser
$env:FLYWAY_DB_PASSWORD = $postgresPassword
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9094'
$env:SERVER_PORT = '8081'
$env:RISK_DETECTION_KAFKA_ENABLED = 'false'
$env:AI_RISK_DETECTION_ENABLED = 'false'
$env:AI_RISK_DETECTION_WORKER_ENABLED = 'false'
$env:PROBLEM_GENERATION_KAFKA_ENABLED = 'true'
$env:AI_PROBLEM_GENERATION_WORKER_ENABLED = 'true'
$env:AI_PROBLEM_GENERATION_BASE_URL = $normalizedAiBaseUrl
$env:AI_PROBLEM_DIAGNOSIS_PATH = '/v1/diagnosis'
$adapterProcess = Start-GradleApplication $adapterRootPath 'adapter'
$startedProcesses += $adapterProcess
$startedProcesses | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding utf8

# Backend process environment
Import-EnvironmentFile (Join-Path $backendRoot '.env')
$env:SPRING_DOCKER_COMPOSE_ENABLED = 'false'
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:TEST_AUTH_ENABLED = 'true'
$env:TEST_ACCOUNT_ID = '0198f000-0000-7000-8000-000000000000'
$env:TEST_TEACHER_PROFILE_ID = '0198f000-0000-7000-8000-000000000001'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9094'
$env:CHECKON_KAFKA_ENABLED = 'false'
$env:RISK_DETECTION_HTTP_ADAPTER_ENABLED = 'false'
$env:AI_PROBLEM_KAFKA_ENABLED = 'true'
$env:AI_PROBLEM_ADAPTER_BASE_URL = 'http://localhost:8081'
$env:AI_PROBLEM_DIAGNOSIS_PATH = '/internal/v1/problem-diagnoses'
$backendProcess = Start-GradleApplication $backendRoot 'backend'
$startedProcesses += $backendProcess
$startedProcesses | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding utf8
try {
	Wait-TcpPort 8081 'Adapter'
	Assert-AdapterDiagnosisRoute
	Wait-TcpPort 8080 'Backend'
	if (-not $SkipSeed) {
		Push-Location $backendRoot
		try {
			Import-EnvironmentFile (Join-Path $backendRoot '.env')
			& (Join-Path $PSScriptRoot 'seed-local-studio.ps1')
		}
		finally { Pop-Location }
	}
}
catch {
	Write-Warning "시작 확인 실패. 상태 파일과 로그는 보존합니다: $stateDirectory"
	throw
}

Write-Host ''
Write-Host '문제 출제 로컬 E2E 환경이 준비되었습니다.'
Write-Host 'Backend: http://localhost:8080'
Write-Host 'Adapter: http://localhost:8081'
Write-Host "AI:      $normalizedAiBaseUrl"
Write-Host '테스트 학생 ID: 0198f000-0000-7000-8000-000000000002'
Write-Host "로그: $stateDirectory"
Write-Host '종료: .\scripts\problem-generation\stop-local-e2e.ps1'
