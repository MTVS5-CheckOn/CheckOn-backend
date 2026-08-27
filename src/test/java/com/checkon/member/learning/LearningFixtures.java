package com.checkon.member.learning;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * learning 통합·리포지토리 테스트가 공유하는 픽스처 삽입 helper. 🔴 <b>관리자 커넥션</b>으로만
 * 부른다 — 앱 커넥션은 RLS 가 걸려 있어 학생 self 정책 밖의 시드가 통과하지 않는다.
 *
 * <p>왜 static — 이 helper 들은 상태가 없고 세 테스트가 같은 조립을 원한다. record 로 묶으면
 * 팩토리 인자가 6개까지 늘어 오히려 시야가 나빠진다.</p>
 */
final class LearningFixtures {

	private LearningFixtures() {
	}

	static UUID insertProblemRequest(
		JdbcTemplate admin, UUID teacher, UUID student, OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_generation_requests (id, teacher_id, tenant_alias,"
			+ " target_kind, student_id, target_ref, ai_idempotency_key, snapshot_hash,"
			+ " request_payload, status, requested_at, updated_at)"
			+ " VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)",
			id, teacher, "tn_" + hex(), student, "st_" + hex(),
			"pg_" + hex(), "sha256:" + hex() + hex(), now, now);
		return id;
	}

	static UUID insertProblemSet(
		JdbcTemplate admin, UUID teacher, UUID requestId, OffsetDateTime now
	) {
		UUID setId = UUID.randomUUID();
		admin.update("INSERT INTO saved_problem_sets (id, teacher_id, problem_request_id, status,"
			+ " saved_at, updated_at) VALUES (?, ?, ?, 'SAVED', ?, ?)",
			setId, teacher, requestId, now, now);
		return setId;
	}

	static UUID insertProblemItem(
		JdbcTemplate admin, UUID teacher, UUID requestId, int ordinal, OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_generation_items (id, teacher_id, problem_request_id,"
			+ " ordinal, stem, validation_status, raw_payload, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?, '문항', 'PASSED', '{}'::jsonb, ?, ?)",
			id, teacher, requestId, ordinal, now, now);
		return id;
	}

	/** 완성된 스냅샷 (correctNo · areaTag · typeTag · skillNodeId 전부 non-null). */
	static void insertGradableItem(
		JdbcTemplate admin,
		UUID teacher, UUID requestId, UUID setId, UUID itemId, int ordinal, int correctNo
	) {
		String snapshot = String.format("""
			{"itemId":"%s","ordinal":%d,"skillNodeId":"L1","areaTag":"reading",
			 "typeTag":"fact","stem":"본문 %d","passage":null,"correctNo":%d,
			 "correctAnswerText":"보기%d","explanation":"해설 %d","validationStatus":"PASSED",
			 "options":[{"position":1,"content":"보기1","whyWrong":null,"misconceptionTag":null},
			            {"position":2,"content":"보기2","whyWrong":null,"misconceptionTag":"MC"},
			            {"position":3,"content":"보기3","whyWrong":null,"misconceptionTag":"MC"}]}
			""", itemId, ordinal, ordinal, correctNo, correctNo, ordinal);
		admin.update("INSERT INTO saved_problem_set_items (problem_set_id, item_id, teacher_id,"
			+ " problem_request_id, ordinal, item_snapshot) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
			setId, itemId, teacher, requestId, ordinal, snapshot);
	}

	/** {@code correctNo}·{@code areaTag}·{@code typeTag}·{@code skillNodeId} 중 하나 이상 null. */
	static void insertUngradableItem(
		JdbcTemplate admin, UUID teacher, UUID requestId, UUID setId, UUID itemId, int ordinal
	) {
		String snapshot = String.format("""
			{"itemId":"%s","ordinal":%d,"stem":"본문 X","passage":null,
			 "correctNo":null,"areaTag":null,"typeTag":null,"skillNodeId":null,
			 "options":[{"position":1,"content":"보기"}]}
			""", itemId, ordinal);
		admin.update("INSERT INTO saved_problem_set_items (problem_set_id, item_id, teacher_id,"
			+ " problem_request_id, ordinal, item_snapshot) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
			setId, itemId, teacher, requestId, ordinal, snapshot);
	}

	static UUID insertAssignment(
		JdbcTemplate admin, UUID teacher, UUID requestId, UUID setId, UUID student,
		OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_assignments (id, teacher_id, problem_request_id,"
			+ " problem_set_id, student_id, status, published_at)"
			+ " VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?)",
			id, teacher, requestId, setId, student, now);
		return id;
	}

	static void activateStudent(JdbcTemplate admin, UUID studentId, OffsetDateTime now) {
		admin.update("INSERT INTO member_student_activation (student_id, status, activated_at,"
			+ " created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?, ?)",
			studentId, now, now, now);
	}

	private static String hex() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
