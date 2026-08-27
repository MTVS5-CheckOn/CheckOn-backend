BEGIN;

INSERT INTO accounts (id, email, role, status, created_at)
VALUES ('0198f000-0000-7000-8000-000000000000', 'parent-counsel-demo-teacher@checkon.local', 'TEACHER', 'ACTIVE', '2026-08-26T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO teacher_profiles (id, account_id, display_name, created_at, updated_at)
VALUES ('0198f000-0000-7000-8000-000000000001', '0198f000-0000-7000-8000-000000000000', '상담 데모 강사', '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO accounts (id, email, role, status, created_at)
VALUES ('0198f000-0000-7000-8000-000000000019', 'parent-counsel-demo-parent@checkon.local', 'PARENT', 'ACTIVE', '2026-08-26T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO parent_profiles (id, account_id, created_at, updated_at)
VALUES ('0198f000-0000-7000-8000-000000000010', '0198f000-0000-7000-8000-000000000019', '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
ON CONFLICT (id) DO UPDATE SET updated_at = EXCLUDED.updated_at;

INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
VALUES ('0198f000-0000-7000-8000-000000000020', '상담 데모 학생', 2, '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
ON CONFLICT (id) DO UPDATE SET grade = EXCLUDED.grade, updated_at = EXCLUDED.updated_at;

INSERT INTO class_groups (id, teacher_id, name, subject, memo, status, created_at, updated_at)
VALUES ('0198f000-0000-7000-8000-000000000021', '0198f000-0000-7000-8000-000000000001', '상담 데모 고2 수학반', '수학', '학부모 상담 로컬 데모 전용', 'ACTIVE', '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, subject = EXCLUDED.subject, memo = EXCLUDED.memo, status = 'ACTIVE', updated_at = EXCLUDED.updated_at;

INSERT INTO teacher_student_relationships
    (id, teacher_id, student_id, status, started_at, ended_at, created_at)
VALUES ('0198f000-0000-7000-8000-000000000022', '0198f000-0000-7000-8000-000000000001', '0198f000-0000-7000-8000-000000000020', 'ACTIVE', '2026-08-01T00:00:00Z', NULL, '2026-08-01T00:00:00Z')
ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', ended_at = NULL;

INSERT INTO class_enrollments
    (id, class_group_id, teacher_id, student_id, status, enrolled_at, ended_at, created_at)
VALUES ('0198f000-0000-7000-8000-000000000023', '0198f000-0000-7000-8000-000000000021', '0198f000-0000-7000-8000-000000000001', '0198f000-0000-7000-8000-000000000020', 'ACTIVE', '2026-08-01T00:00:00Z', NULL, '2026-08-01T00:00:00Z')
ON CONFLICT (id) DO UPDATE SET class_group_id = EXCLUDED.class_group_id, status = 'ACTIVE', ended_at = NULL;

INSERT INTO student_personal_information
    (student_id, real_name, updated_by_account_id, updated_by_role, created_at, updated_at)
VALUES ('0198f000-0000-7000-8000-000000000020', '김체크', '0198f000-0000-7000-8000-000000000000', 'TEACHER', '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
ON CONFLICT (student_id) DO UPDATE SET real_name = EXCLUDED.real_name, updated_at = EXCLUDED.updated_at;

INSERT INTO parent_teacher_relationships
    (id, parent_id, teacher_id, status, started_at, ended_at, created_at)
VALUES ('0198f000-0000-7000-8000-000000000024', '0198f000-0000-7000-8000-000000000010', '0198f000-0000-7000-8000-000000000001', 'ACTIVE', '2026-08-01T00:00:00Z', NULL, '2026-08-01T00:00:00Z')
ON CONFLICT DO NOTHING;

INSERT INTO parent_student_relationships
    (id, parent_id, student_id, status, started_at, ended_at, created_at)
VALUES ('0198f000-0000-7000-8000-000000000025', '0198f000-0000-7000-8000-000000000010', '0198f000-0000-7000-8000-000000000020', 'ACTIVE', '2026-08-01T00:00:00Z', NULL, '2026-08-01T00:00:00Z')
ON CONFLICT DO NOTHING;

INSERT INTO counsel_inquiries (
    id, teacher_id, inquiry_ref, student_id, class_id, topic, urgency, received_at,
    raw_text, labels, dismissed_suggestions, period_label, facts, created_at, updated_at
) VALUES
('0198f000-0000-7000-8000-000000000101', '0198f000-0000-7000-8000-000000000001', 'demo-counsel-01', '0198f000-0000-7000-8000-000000000020', '0198f000-0000-7000-8000-000000000021', 'grade', 'normal', '2026-08-20T01:00:00Z', '최근 수학 성적 흐름과 보완 계획을 알고 싶습니다.', '["data","direct"]', '[]', '2026년 8월', '[{"recordId":"demo-learning-01","summary":"최근 평가 3회 평균은 82점입니다."}]', '2026-08-20T01:00:00Z', '2026-08-20T01:00:00Z'),
('0198f000-0000-7000-8000-000000000102', '0198f000-0000-7000-8000-000000000001', 'demo-counsel-02', '0198f000-0000-7000-8000-000000000020', '0198f000-0000-7000-8000-000000000021', 'schedule', 'normal', '2026-08-21T01:00:00Z', '다음 보충 수업 일정을 확인하고 싶습니다.', '["data","direct"]', '[]', '2026년 8월', '[{"recordId":"demo-learning-02","summary":"최근 과제 제출률은 90퍼센트입니다."}]', '2026-08-21T01:00:00Z', '2026-08-21T01:00:00Z'),
('0198f000-0000-7000-8000-000000000103', '0198f000-0000-7000-8000-000000000001', 'demo-counsel-03', '0198f000-0000-7000-8000-000000000020', '0198f000-0000-7000-8000-000000000021', 'counsel_request', 'normal', '2026-08-22T01:00:00Z', '학습 태도에 대해 상담을 요청합니다.', '["data","direct"]', '[]', '2026년 8월', '[{"recordId":"demo-learning-03","summary":"최근 4주간 지각 없이 출석했습니다."}]', '2026-08-22T01:00:00Z', '2026-08-22T01:00:00Z'),
('0198f000-0000-7000-8000-000000000104', '0198f000-0000-7000-8000-000000000001', 'demo-counsel-04', '0198f000-0000-7000-8000-000000000020', '0198f000-0000-7000-8000-000000000021', 'grade', 'immediate', '2026-08-23T01:00:00Z', '이번 주 평가 결과와 오답 보완 여부가 궁금합니다.', '["data","direct"]', '[]', '2026년 8월', '[{"recordId":"demo-learning-04","summary":"이번 주 평가에서 대수 영역 정답률은 75퍼센트입니다."}]', '2026-08-23T01:00:00Z', '2026-08-23T01:00:00Z'),
('0198f000-0000-7000-8000-000000000105', '0198f000-0000-7000-8000-000000000001', 'demo-counsel-05', '0198f000-0000-7000-8000-000000000020', '0198f000-0000-7000-8000-000000000021', 'etc', 'normal', '2026-08-24T01:00:00Z', '학원 생활 전반에 특이사항이 있는지 알려주세요.', '["data","direct"]', '[]', '2026년 8월', '[{"recordId":"demo-learning-05","summary":"최근 한 달간 학습 기록은 18건입니다."}]', '2026-08-24T01:00:00Z', '2026-08-24T01:00:00Z'),
('0198f000-0000-7000-8000-000000000106', '0198f000-0000-7000-8000-000000000001', 'demo-counsel-start', '0198f000-0000-7000-8000-000000000020', '0198f000-0000-7000-8000-000000000021', 'grade', 'normal', '2026-08-25T01:00:00Z', '다음 상담에서 최근 성적과 보완 계획을 함께 안내해 주세요.', '["data","direct"]', '[]', '2026년 8월', '[{"recordId":"demo-learning-start","summary":"최근 평가 3회 평균은 84점이고 오답 복습 완료율은 80퍼센트입니다."}]', '2026-08-25T01:00:00Z', '2026-08-25T01:00:00Z')
ON CONFLICT (teacher_id, inquiry_ref) DO UPDATE SET
    student_id = EXCLUDED.student_id, class_id = EXCLUDED.class_id,
    topic = EXCLUDED.topic, urgency = EXCLUDED.urgency, received_at = EXCLUDED.received_at,
    raw_text = EXCLUDED.raw_text, labels = EXCLUDED.labels,
    dismissed_suggestions = EXCLUDED.dismissed_suggestions,
    period_label = EXCLUDED.period_label, facts = EXCLUDED.facts, updated_at = EXCLUDED.updated_at;

INSERT INTO counsel_draft_jobs (
    id, teacher_id, tenant_alias, inquiry_ref, student_ref, parent_ref, class_ref, topic,
    idempotency_key, job_id, job_phase, request_hash, requested_at, updated_at, sent_text, sent_at
) VALUES
('0198f000-0000-7000-8000-000000000201', '0198f000-0000-7000-8000-000000000001', 'tn_parent_counsel_demo', 'demo-counsel-01', 'st_parent_counsel_demo', 'pa_parent_counsel_demo', 'cl_parent_counsel_demo', 'grade', 'demo-counsel-idem-01', 'demo-counsel-job-01', 'succeeded', 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '2026-08-20T01:01:00Z', '2026-08-20T01:02:00Z', '최근 평가 평균은 82점이며 오답 복습 계획을 안내드리겠습니다.', '2026-08-20T01:03:00Z'),
('0198f000-0000-7000-8000-000000000202', '0198f000-0000-7000-8000-000000000001', 'tn_parent_counsel_demo', 'demo-counsel-02', 'st_parent_counsel_demo', 'pa_parent_counsel_demo', 'cl_parent_counsel_demo', 'schedule', 'demo-counsel-idem-02', 'demo-counsel-job-02', 'succeeded', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', '2026-08-21T01:01:00Z', '2026-08-21T01:02:00Z', '다음 보충 수업은 목요일 오후 7시에 진행됩니다.', '2026-08-21T01:03:00Z'),
('0198f000-0000-7000-8000-000000000203', '0198f000-0000-7000-8000-000000000001', 'tn_parent_counsel_demo', 'demo-counsel-03', 'st_parent_counsel_demo', 'pa_parent_counsel_demo', 'cl_parent_counsel_demo', 'counsel_request', 'demo-counsel-idem-03', 'demo-counsel-job-03', 'succeeded', 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', '2026-08-22T01:01:00Z', '2026-08-22T01:02:00Z', '최근 출석과 학습 태도는 안정적이며 다음 상담에서 자세히 안내드리겠습니다.', '2026-08-22T01:03:00Z'),
('0198f000-0000-7000-8000-000000000204', '0198f000-0000-7000-8000-000000000001', 'tn_parent_counsel_demo', 'demo-counsel-04', 'st_parent_counsel_demo', 'pa_parent_counsel_demo', 'cl_parent_counsel_demo', 'grade', 'demo-counsel-idem-04', 'demo-counsel-job-04', 'succeeded', 'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', '2026-08-23T01:01:00Z', '2026-08-23T01:02:00Z', '대수 영역 오답을 중심으로 이번 주 보완 학습을 진행하겠습니다.', '2026-08-23T01:03:00Z'),
('0198f000-0000-7000-8000-000000000205', '0198f000-0000-7000-8000-000000000001', 'tn_parent_counsel_demo', 'demo-counsel-05', 'st_parent_counsel_demo', 'pa_parent_counsel_demo', 'cl_parent_counsel_demo', 'etc', 'demo-counsel-idem-05', 'demo-counsel-job-05', 'succeeded', 'sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', '2026-08-24T01:01:00Z', '2026-08-24T01:02:00Z', '학원 생활에 특별한 문제는 없으며 꾸준히 학습하고 있습니다.', '2026-08-24T01:03:00Z')
ON CONFLICT (teacher_id, idempotency_key) DO UPDATE SET
    inquiry_ref = EXCLUDED.inquiry_ref, topic = EXCLUDED.topic,
    job_phase = EXCLUDED.job_phase, request_hash = EXCLUDED.request_hash,
    requested_at = EXCLUDED.requested_at, updated_at = EXCLUDED.updated_at,
    sent_text = EXCLUDED.sent_text, sent_at = EXCLUDED.sent_at;

INSERT INTO guardian_labels
    (id, teacher_id, parent_id, axis, value, source_suggestion_id, created_at, updated_at)
VALUES
('0198f000-0000-7000-8000-000000000301', '0198f000-0000-7000-8000-000000000001', '0198f000-0000-7000-8000-000000000010', 'comm', 'data', 'demo-parent:comm:data', '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z'),
('0198f000-0000-7000-8000-000000000302', '0198f000-0000-7000-8000-000000000001', '0198f000-0000-7000-8000-000000000010', 'sensitivity', 'direct', 'demo-parent:sensitivity:direct', '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
ON CONFLICT (teacher_id, parent_id, axis) DO UPDATE SET
    value = EXCLUDED.value, source_suggestion_id = EXCLUDED.source_suggestion_id, updated_at = EXCLUDED.updated_at;

COMMIT;
