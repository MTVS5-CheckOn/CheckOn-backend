[CmdletBinding()]
param(
	[string]$EnvironmentFile
)

$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
	Join-Path $backendRoot '.env'
}
else { (Resolve-Path $EnvironmentFile).Path }
$teacherId = '0198f000-0000-7000-8000-000000000001'
$studentId = '0198f000-0000-7000-8000-000000000002'
$classId = '0198f000-0000-7000-8000-000000000003'
$occurredAt = (Get-Date).ToUniversalTime().AddDays(-1).ToString('yyyy-MM-ddTHH:mm:ssZ')
$now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')

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

$values = for ($index = 1; $index -le 24; $index++) {
	$id = '0198f000-0000-7000-8001-{0:d12}' -f $index
	$type = if ($index -le 12) { 'concept' } else { 'infer' }
	$correct = if (($index % 3) -eq 0) { 'true' } else { 'false' }
	"('$id','$teacherId','$studentId','$classId','SOLVE','$occurredAt','problem-studio-e2e',$correct,'language','$type','mcq','$now','$now')"
}

$sql = @"
BEGIN;
DELETE FROM learning_records
WHERE teacher_id='$teacherId'::uuid AND source_type='problem-studio-e2e';
INSERT INTO learning_records
    (id,teacher_id,student_id,class_group_id,record_type,occurred_at,source_type,
     correct,area_tag,type_tag,item_format,created_at,updated_at)
VALUES
$($values -join ",`n");
COMMIT;
"@

Push-Location $backendRoot
try {
	# 공통 dev principal, 학생, 반을 멱등하게 준비한다.
	& (Join-Path $PSScriptRoot '..\seed-risk-detection-demo-data.ps1') `
		-AnalysisDate (Get-Date -Format 'yyyy-MM-dd')

	$sql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 `
		-U $env:POSTGRES_USER -d $env:POSTGRES_DB
	if ($LASTEXITCODE -ne 0) { throw '문제 출제 로컬 fixture 준비에 실패했습니다.' }

	$recordCount = docker compose exec -T postgres psql -tA -v ON_ERROR_STOP=1 `
		-U $env:POSTGRES_USER -d $env:POSTGRES_DB `
		-c "SELECT count(*) FROM learning_records WHERE teacher_id='$teacherId'::uuid AND source_type='problem-studio-e2e';"
	if ($LASTEXITCODE -ne 0 -or [int]$recordCount -ne 24) {
		throw "문제 출제 fixture 건수가 24건이 아닙니다: $recordCount"
	}
}
finally { Pop-Location }

Write-Host '문제 출제 fixture 준비 완료: language/concept 12건, language/infer 12건'
