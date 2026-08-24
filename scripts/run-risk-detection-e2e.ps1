[CmdletBinding()]
param(
	[Parameter(Mandatory)]
	[string]$AnalysisDate,
	[string]$EnvironmentFile,
	[string]$BackendBaseUrl = 'http://127.0.0.1:8080',
	[string]$AiBaseUrl = 'http://127.0.0.1:8000',
	[string]$AdapterHost = '127.0.0.1',
	[int]$AdapterPort = 8081,
	[string]$PostgresContainer = 'checkon-svc-postgres-1',
	[int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = if ($EnvironmentFile) { $EnvironmentFile } else { Join-Path $projectRoot '.env' }
$seedPath = Join-Path $PSScriptRoot 'demo\risk-detection\checkon_seed.sql'

try {
	$analysisDay = [datetime]::ParseExact(
		$AnalysisDate,
		'yyyy-MM-dd',
		[System.Globalization.CultureInfo]::InvariantCulture
	)
}
catch {
	throw 'AnalysisDate는 yyyy-MM-dd 형식이어야 합니다.'
}

if (-not (Test-Path -LiteralPath $envFile)) {
	throw ".env 파일을 찾을 수 없습니다: $envFile"
}
if (-not (Test-Path -LiteralPath $seedPath)) {
	throw "17명 시드 SQL을 찾을 수 없습니다: $seedPath"
}

Get-Content -LiteralPath $envFile | ForEach-Object {
	if ($_ -match '^\s*([^#=\s]+)\s*=(.*)$') {
		[Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
	}
}

foreach ($name in @('POSTGRES_DB', 'POSTGRES_USER')) {
	if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
		throw "$name 환경 변수가 비어 있습니다."
	}
}

# This fixture uses a tenant that is deliberately separate from the default
# local demo principal so existing developer data is never deleted or reused.
$accountId = '0198f000-0000-7000-8000-000000009000'
$teacherId = '0198f000-0000-7000-8000-000000009001'
$classAId = '0198f000-0000-7000-8000-000000009201'
$classBId = '0198f000-0000-7000-8000-000000009202'

$daysFromMonday = (([int]$analysisDay.DayOfWeek + 6) % 7)
$monday = $analysisDay.AddDays(-$daysFromMonday).Date
$mondayText = $monday.ToString('yyyy-MM-dd')
$establishedAt = $monday.AddDays(-84).ToString('yyyy-MM-ddT00:00:00+09:00')
$newStudentAt = $monday.ToString('yyyy-MM-ddT00:00:00+09:00')
$now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')

$studentRows = [System.Collections.Generic.List[string]]::new()
$relationshipRows = [System.Collections.Generic.List[string]]::new()
$enrollmentRows = [System.Collections.Generic.List[string]]::new()
$aliasRows = [System.Collections.Generic.List[string]]::new()
$personalInformationRows = [System.Collections.Generic.List[string]]::new()
$studentAliases = @{}

for ($number = 1; $number -le 17; $number++) {
	$suffix = $number.ToString('00')
	$studentId = "0198f000-0000-7000-8000-0000000091$suffix"
	$relationshipId = "0198f000-0000-7000-8000-0000000093$suffix"
	$enrollmentId = "0198f000-0000-7000-8000-0000000094$suffix"
	$aliasId = "0198f000-0000-7000-8000-0000000095$suffix"
	$realAlias = 'st_' + $studentId.Replace('-', '')
	$startedAt = if ($number -eq 9) { $newStudentAt } else { $establishedAt }
	$studentAliases["st_$suffix"] = $realAlias

	$studentRows.Add("('$studentId', '위험탐지 시연 학생 $suffix', 1, '$now', '$now')")
	$relationshipRows.Add("('$relationshipId', '$teacherId', '$studentId', 'ACTIVE', '$startedAt', '$now')")
	$aliasRows.Add("('$aliasId', '$teacherId', '$studentId', '$realAlias', '$now')")
	$personalInformationRows.Add("('$studentId', '위험탐지 시연 학생 $suffix', '$accountId', 'TEACHER', '$now', '$now')")

	if ($number -ne 16) {
		$classId = if ($number -le 9) { $classAId } else { $classBId }
		$enrollmentRows.Add("('$enrollmentId', '$classId', '$teacherId', '$studentId', 'ACTIVE', '$startedAt', '$now')")
	}
}

$rosterSql = @"
BEGIN;

INSERT INTO accounts (id, email, role, status, created_at)
VALUES ('$accountId', 'risk-detection-e2e@checkon.local', 'TEACHER', 'ACTIVE', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO teacher_profiles (id, account_id, display_name, created_at, updated_at)
VALUES ('$teacherId', '$accountId', '위험탐지 E2E 강사', '$now', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO class_groups (id, teacher_id, name, subject, status, created_at, updated_at)
VALUES
  ('$classAId', '$teacherId', '위험탐지 시연 A반', '국어', 'ACTIVE', '$now', '$now'),
  ('$classBId', '$teacherId', '위험탐지 시연 B반', '국어', 'ACTIVE', '$now', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO student_profiles (id, alias, grade, created_at, updated_at) VALUES
$($studentRows -join ",`n")
ON CONFLICT (id) DO NOTHING;

INSERT INTO teacher_student_relationships
  (id, teacher_id, student_id, status, started_at, created_at) VALUES
$($relationshipRows -join ",`n")
ON CONFLICT (id) DO NOTHING;

INSERT INTO student_personal_information
  (student_id, real_name, updated_by_account_id, updated_by_role, created_at, updated_at) VALUES
$($personalInformationRows -join ",`n")
ON CONFLICT (student_id) DO NOTHING;

INSERT INTO class_enrollments
  (id, class_group_id, teacher_id, student_id, status, enrolled_at, created_at) VALUES
$($enrollmentRows -join ",`n")
ON CONFLICT (id) DO NOTHING;

INSERT INTO ai_student_aliases
  (id, teacher_id, student_id, alias, created_at) VALUES
$($aliasRows -join ",`n")
ON CONFLICT (id) DO NOTHING;

COMMIT;
"@

function Invoke-PsqlInput([string]$sql) {
	$previousOutputEncoding = $OutputEncoding
	try {
		$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
		$sql | & docker exec -i $PostgresContainer psql `
			-v ON_ERROR_STOP=1 `
			-U $env:POSTGRES_USER `
			-d $env:POSTGRES_DB
		if ($LASTEXITCODE -ne 0) {
			throw 'PostgreSQL SQL 실행에 실패했습니다.'
		}
	}
	finally {
		$OutputEncoding = $previousOutputEncoding
	}
}

function Invoke-PsqlScalar([string]$sql) {
	$result = & docker exec $PostgresContainer psql `
		-tA `
		-v ON_ERROR_STOP=1 `
		-U $env:POSTGRES_USER `
		-d $env:POSTGRES_DB `
		-c $sql
	if ($LASTEXITCODE -ne 0) {
		throw 'PostgreSQL 조회에 실패했습니다.'
	}
	return ($result | Select-Object -First 1).Trim()
}

foreach ($healthUrl in @("$BackendBaseUrl/v3/api-docs", "$AiBaseUrl/v1/health")) {
	try {
		$null = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 5
	}
	catch {
		throw "필수 서비스가 응답하지 않습니다: $healthUrl"
	}
}

$adapterConnection = [System.Net.Sockets.TcpClient]::new()
try {
	$connect = $adapterConnection.BeginConnect($AdapterHost, $AdapterPort, $null, $null)
	if (-not $connect.AsyncWaitHandle.WaitOne(3000) -or -not $adapterConnection.Connected) {
		throw 'connection timeout'
	}
	$adapterConnection.EndConnect($connect)
}
catch {
	throw "독립 Kafka Adapter가 응답하지 않습니다: ${AdapterHost}:$AdapterPort"
}
finally {
	$adapterConnection.Dispose()
}

$existingRecords = Invoke-PsqlScalar (
	"SELECT count(*) FROM learning_records WHERE teacher_id = '$teacherId'::uuid " +
	"AND occurred_at >= ('$mondayText'::date - 77) AND occurred_at < ('$mondayText'::date + 7);"
)
Write-Host '전용 Roster와 AI alias를 준비합니다.'
Invoke-PsqlInput $rosterSql

if ([int]$existingRecords -gt 0) {
	Write-Host "기존 전용 시드 $existingRecords 건을 재사용합니다. 학습 기록은 중복 적재하지 않습니다."
}
else {
	$seedSql = Get-Content -Raw -LiteralPath $seedPath
	$seedSql = $seedSql.Replace(
		"\set teacher_id '0198f000-0000-7000-8000-000000000001'",
		"\set teacher_id '$teacherId'"
	)
	$seedSql = $seedSql.Replace("\set monday     '2026-08-10'", "\set monday     '$mondayText'")
	# psql does not interpolate :variables inside the seed's DO $$ ... $$ block.
	# Replacing every typed reference also keeps the same SQL valid in those blocks.
	$seedSql = $seedSql.Replace(":'teacher_id'::uuid", "'$teacherId'::uuid")
	$seedSql = $seedSql.Replace(":'monday'::date", "'$mondayText'::date")
	foreach ($docAlias in $studentAliases.Keys) {
		$seedSql = $seedSql.Replace("('$docAlias', '<채우세요>')", "('$docAlias', '$($studentAliases[$docAlias])')")
	}
	$seedSql = $seedSql.Replace("('cl_a1', NULL)", "('cl_a1', '$classAId')")
	$seedSql = $seedSql.Replace("('cl_b2', NULL)", "('cl_b2', '$classBId')")
	$seedSql += "`nCOMMIT;`n"

	Write-Host "17명·12주 학습/과제/복귀 시드를 적재합니다. 기준 주: $mondayText"
	Invoke-PsqlInput $seedSql
}

$runId = Invoke-PsqlScalar (
	"SELECT coalesce((SELECT id::text FROM detection_runs WHERE teacher_id = '$teacherId'::uuid " +
	"AND analysis_date = '$AnalysisDate'::date), '');"
)
if ([string]::IsNullOrWhiteSpace($runId)) {
	$requestBody = @{ analysisDate = $AnalysisDate; termContext = 'normal' } | ConvertTo-Json
	$run = Invoke-RestMethod `
		-Method Post `
		-Uri "$BackendBaseUrl/api/v1/detection-runs" `
		-ContentType 'application/json; charset=utf-8' `
		-Body $requestBody
	$runId = $run.runId
	if ([string]::IsNullOrWhiteSpace($runId)) {
		throw 'Detection 실행 응답에 runId가 없습니다.'
	}
}
else {
	Write-Host "동일 강사·분석일의 기존 Detection 실행을 재사용합니다: $runId"
}

Write-Host "Detection 실행을 요청했습니다: $runId"
$deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
	Start-Sleep -Seconds 1
	$detail = Invoke-RestMethod -Method Get -Uri "$BackendBaseUrl/api/v1/detection-runs/$runId"
	if ($detail.status -in @('SUCCEEDED', 'FAILED')) { break }
} while ([datetime]::UtcNow -lt $deadline)

if ($detail.status -ne 'SUCCEEDED') {
	throw "Detection 실행이 성공하지 못했습니다. runId=$runId status=$($detail.status)"
}

$signalCounts = Invoke-PsqlScalar (
	"SELECT coalesce(string_agg(rule_id || '=' || count, ', ' ORDER BY rule_id), 'none') " +
	"FROM (SELECT rule_id, count(*)::text AS count FROM detection_signal_results " +
	"WHERE detection_run_id = '$runId'::uuid GROUP BY rule_id) rules;"
)
$evidenceCount = Invoke-PsqlScalar (
	"SELECT count(*) FROM detection_result_evidence evidence " +
	"JOIN detection_signal_results signal ON signal.id = evidence.detection_signal_result_id " +
	"WHERE signal.detection_run_id = '$runId'::uuid;"
)
$alertCount = Invoke-PsqlScalar (
	"SELECT count(*) FROM engagement_alerts alert JOIN detection_signal_results signal " +
	"ON signal.id = alert.detection_signal_result_id WHERE signal.detection_run_id = '$runId'::uuid;"
)
$lifecycleCounts = Invoke-PsqlScalar (
	"SELECT coalesce(string_agg(lifecycle || '=' || count, ', ' ORDER BY lifecycle), 'none') " +
	"FROM (SELECT lifecycle, count(*)::text AS count FROM detection_signal_results " +
	"WHERE detection_run_id = '$runId'::uuid GROUP BY lifecycle) lifecycle_counts;"
)

[pscustomobject]@{
	runId = $runId
	status = $detail.status
	rulesSkipped = $detail.stats.rulesSkipped
	r1ThresholdPp = $detail.stats.r1ThresholdPp
	r1ThresholdSource = $detail.stats.r1ThresholdSource
	r1PoolN = $detail.stats.r1PoolN
	cappedOut = $detail.stats.cappedOut
	signalsByRule = $signalCounts
	signalsByLifecycle = $lifecycleCounts
	evidenceCount = [int]$evidenceCount
	alertCount = [int]$alertCount
	teacherProfileId = $teacherId
} | ConvertTo-Json -Depth 10
