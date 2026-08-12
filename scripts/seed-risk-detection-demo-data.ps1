[CmdletBinding()]
param(
	[Parameter(Mandatory)]
	[string]$AnalysisDate
)

$ErrorActionPreference = 'Stop'

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

foreach ($name in @('POSTGRES_DB', 'POSTGRES_USER')) {
	if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
		throw "$name 환경 변수가 비어 있습니다. .env를 확인하세요."
	}
}

# These UUIDs are used only by the local dev profile. They deliberately match
# application-dev.yaml's test-authentication principal and do not contain real
# teacher or student data.
$accountId = '0198f000-0000-7000-8000-000000000000'
$teacherId = '0198f000-0000-7000-8000-000000000001'
$studentId = '0198f000-0000-7000-8000-000000000002'
$classId = '0198f000-0000-7000-8000-000000000003'
$relationshipId = '0198f000-0000-7000-8000-000000000004'
$enrollmentId = '0198f000-0000-7000-8000-000000000005'
$recordId = '0198f000-0000-7000-8000-000000000006'
$occurredAt = $analysisDay.AddHours(9).ToString('yyyy-MM-ddTHH:mm:ss+09:00')
$now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')

$seedSql = @"
BEGIN;

INSERT INTO accounts (id, email, role, status, created_at)
VALUES ('$accountId', 'kafka-demo-teacher@checkon.local', 'TEACHER', 'ACTIVE', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO teacher_profiles (id, account_id, display_name, created_at, updated_at)
VALUES ('$teacherId', '$accountId', 'Kafka 시연 강사', '$now', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
VALUES ('$studentId', 'Kafka 시연 학생', 1, '$now', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO class_groups (id, teacher_id, name, subject, status, created_at, updated_at)
VALUES ('$classId', '$teacherId', 'Kafka 시연 반', '국어', 'ACTIVE', '$now', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO teacher_student_relationships
    (id, teacher_id, student_id, status, started_at, created_at)
VALUES ('$relationshipId', '$teacherId', '$studentId', 'ACTIVE', '$now', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO class_enrollments
    (id, class_group_id, teacher_id, student_id, status, enrolled_at, created_at)
VALUES ('$enrollmentId', '$classId', '$teacherId', '$studentId', 'ACTIVE', '$now', '$now')
ON CONFLICT (id) DO NOTHING;

INSERT INTO learning_records
    (id, teacher_id, student_id, class_group_id, record_type, occurred_at,
     source_type, external_record_ref, correct, duration_sec,
     passage_word_count, area_tag, subject_track, type_tag, item_format,
     created_at, updated_at)
VALUES ('$recordId', '$teacherId', '$studentId', '$classId', 'SOLVE', '$occurredAt',
        'studentHome', 'kafka-demo-$AnalysisDate', true, 180,
        800, 'reading', 'common', 'infer', 'mcq', '$now', '$now')
ON CONFLICT (id) DO UPDATE
SET occurred_at = EXCLUDED.occurred_at,
    source_type = EXCLUDED.source_type,
    updated_at = EXCLUDED.updated_at,
    external_record_ref = EXCLUDED.external_record_ref;

COMMIT;
"@

$seedSql | & docker compose exec -T postgres psql `
	-v ON_ERROR_STOP=1 `
	-U $env:POSTGRES_USER `
	-d $env:POSTGRES_DB
if ($LASTEXITCODE -ne 0) {
	throw 'Kafka 시연용 데이터 SQL 실행에 실패했습니다.'
}
