package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;

/** 관리자 커넥션으로 제약을 잠시 깨서 테스트가 실제로 red인지 확인하는 mutation 전용 스위트. */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("dev")
class AttemptConstraintDestructionTest extends MembershipRlsEnforcedSupport {

	@Test
	@EnabledIfEnvironmentVariable(
		named = "CHECKON_MUTATION_DROP_OPEN_INDEX", matches = "true")
	@DisplayName("고의 파괴 — 열린 attempt unique index가 없으면 행 2개를 테스트가 잡는다")
	void droppedOpenIndexTurnsRedAndIsRestored() {
		JdbcTemplate admin = adminJdbcTemplate();
		clearFixtures(admin);
		OffsetDateTime now = OffsetDateTime.now();
		UUID teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
		UUID accountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		UUID studentId = MemberPostgresSupport.insertStudentProfile(
			admin, accountId, "박학생", null, now);
		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentId, now, now);
		UUID requestId = LearningFixtures.insertProblemRequest(admin, teacherId, studentId, now);
		UUID setId = LearningFixtures.insertProblemSet(admin, teacherId, requestId, now);
		UUID assignmentId = LearningFixtures.insertAssignment(
			admin, teacherId, requestId, setId, studentId, now);
		try {
			admin.execute("DROP INDEX uq_member_attempts_open");
			assertThat(indexCount(admin)).as("파괴 적용 확인").isZero();
			insertOpenAttempt(admin, studentId, assignmentId, teacherId, now);
			insertOpenAttempt(admin, studentId, assignmentId, teacherId, now.plusSeconds(1));
			Integer rows = admin.queryForObject(
				"SELECT count(*) FROM member_attempts WHERE assignment_id = ?",
				Integer.class, assignmentId);
			assertThat(rows).as("unique index 파괴를 행 2개로 잡아야 한다").isEqualTo(1);
		}
		finally {
			admin.update("DELETE FROM member_attempts WHERE assignment_id = ?", assignmentId);
			admin.execute("CREATE UNIQUE INDEX uq_member_attempts_open"
				+ " ON member_attempts (student_id, assignment_id)"
				+ " WHERE status = 'IN_PROGRESS'");
			assertThat(indexCount(admin)).as("finally 원복 확인").isEqualTo(1);
		}
	}

	private static void insertOpenAttempt(
		JdbcTemplate admin, UUID studentId, UUID assignmentId, UUID teacherId, OffsetDateTime now
	) {
		admin.update("INSERT INTO member_attempts (id, student_id, assignment_id, teacher_id,"
			+ " status, version, snapshot_hash, item_count, active_elapsed_sec, started_at)"
			+ " VALUES (?, ?, ?, ?, 'IN_PROGRESS', 0, ?, 1, 0, ?)",
			UUID.randomUUID(), studentId, assignmentId, teacherId,
			"sha256:" + "0".repeat(64), now);
	}

	private static Integer indexCount(JdbcTemplate admin) {
		return admin.queryForObject(
			"SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_member_attempts_open'",
			Integer.class);
	}
}
