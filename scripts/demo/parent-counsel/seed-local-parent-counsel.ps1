[CmdletBinding()]
param(
	[string]$EnvironmentFile
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

Get-Content -LiteralPath $envFile | ForEach-Object {
	if ($_ -match '^\s*([^#=\s]+)\s*=(.*)$') {
		[Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
	}
}

if ([string]::IsNullOrWhiteSpace($env:POSTGRES_DB) -or [string]::IsNullOrWhiteSpace($env:POSTGRES_USER)) {
	throw 'POSTGRES_DB와 POSTGRES_USER가 필요합니다.'
}

$teacherId = '0198f000-0000-7000-8000-000000000001'
$parentId = '0198f000-0000-7000-8000-000000000010'
$studentId = '0198f000-0000-7000-8000-000000000020'
$classId = '0198f000-0000-7000-8000-000000000021'
$inquiryRef = 'demo-counsel-start'
$seedFile = Join-Path $PSScriptRoot 'seed.sql'

Push-Location $backendRoot
try {
	Get-Content -Raw -LiteralPath $seedFile | docker compose exec -T postgres psql `
		-v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d $env:POSTGRES_DB
	if ($LASTEXITCODE -ne 0) {
		throw '학부모 상담 데모 씨드 SQL 실행에 실패했습니다.'
	}

	$historyCount = (docker compose exec -T postgres psql -tA -v ON_ERROR_STOP=1 `
		-U $env:POSTGRES_USER -d $env:POSTGRES_DB `
		-c "SELECT count(*) FROM (SELECT inquiry_ref FROM counsel_inquiries WHERE teacher_id='$teacherId'::uuid AND student_id='$studentId'::uuid UNION ALL SELECT job_id FROM counsel_draft_jobs WHERE teacher_id='$teacherId'::uuid AND inquiry_ref LIKE 'demo-counsel-%' AND sent_text IS NOT NULL AND sent_at IS NOT NULL) history;").Trim()
	if ($LASTEXITCODE -ne 0 -or $historyCount -ne '11') {
		throw "상담 이력 씨드 검증에 실패했습니다. Expected=11 Actual=$historyCount"
	}
}
finally {
	Pop-Location
}

Write-Host '학부모 상담 데모 씨드 준비 완료'
Write-Host "parentId=$parentId"
Write-Host "studentId=$studentId"
Write-Host "classId=$classId"
Write-Host "inquiryRef=$inquiryRef"
Write-Host "analyzableCommunicationCount=$historyCount (Guardian Labels에는 최신 10건 사용)"
Write-Host 'API 호출 순서:'
Write-Host '1. GET /api/v1/guardians?page=0&size=20'
Write-Host "2. GET /api/v1/guardians/$parentId/communications?page=0&size=20"
Write-Host '3. GET /api/v1/counsel/inquiries?page=0&size=20'
Write-Host '4. POST /api/v1/counsel/drafts (inquiryRef=demo-counsel-start, 조회 응답의 studentId/classId/topic/urgency/receivedAt/rawText/labels/periodLabel/facts 사용)'
Write-Host '5. 202 Location을 GET으로 폴링'
