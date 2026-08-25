package com.checkon.member.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.support.MemberPostgresSupport;

/**
 * 범위 세션 변수(설계 §6-4-2)가 우회로가 아니라는 것을 증명한다.
 *
 * <p>불변식 4번 때문에 {@code saved_problem_sets} 정책은 {@code problem_assignments} 를
 * 참조할 수 없다. 대신 애플리케이션이 소유를 먼저 확인하고 확인된 id 를
 * {@code checkon.scope_problem_set_id} 에 넣는다. 🔴 이 테스트가 그 방식의 안전 근거다.</p>
 *
 * <p>🔴 <b>PR3 에서 다시 세웠다.</b> 이전 판은 아무것도 증명하지 못했다 —
 * {@code saved_problem_sets} 에 행을 하나도 넣지 않고 {@code isEmpty()} 를 단언했고,
 * 커넥션은 {@code super=true bypassrls=true} 였다(MB-34). <b>RLS 를 통째로 꺼도 통과하는</b>
 * 테스트였다. 정책의 scope 조건을 지우는 고의 파괴가 red 를 못 내는 게 그 증거다.</p>
 *
 * <p>그래서 셋을 바꿨다:</p>
 * <ol>
 *   <li>제한 역할({@code NOSUPERUSER NOBYPASSRLS})로 읽는다</li>
 *   <li>「전제」 단언을 먼저 둔다 — 이 커넥션이 정말 RLS 대상인가</li>
 *   <li><b>행을 실제로 넣는다</b> — 내 problem_set 과 <b>남의 problem_set</b>.
 *       남의 것이 있어야 "안 보인다"가 의미를 갖는다</li>
 * </ol>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("dev")
class MemberScopeSessionVariableIntegrationTest extends MemberPostgresSupport {

	@Autowired JdbcTemplate jdbcTemplate;

	private JdbcTemplate restricted;
	private TransactionTemplate restrictedTransaction;

	private UUID studentId;
	private UUID problemSetId;
	private UUID otherProblemSetId;

	@BeforeEach
	void setUp() {
		restricted = restrictedJdbcTemplate(jdbcTemplate);
		restrictedTransaction =
			new TransactionTemplate(new JdbcTransactionManager(restricted.getDataSource()));

		clearMemberFixtures(jdbcTemplate);

		OffsetDateTime now = OffsetDateTime.now();
		UUID teacherAccountId = insertAccount("teacher@example.com", "TEACHER", now);
		UUID teacherId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO teacher_profiles (id, account_id, display_name, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?)", teacherId, teacherAccountId, "김강사", now, now);

		UUID studentAccountId = insertAccount("student@example.com", "STUDENT", now);
		studentId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO student_profiles (id, account_id, alias, account_linked_at,"
				+ " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
			studentId, studentAccountId, "김학생", now, now, now);

		// 🔴 check_problem_generation_target_ownership() 트리거가 활성 강사 관계를 요구한다.
		//    없으면 INSERT 자체가 거절된다(실측) — 픽스처가 제품 제약을 우회하지 않는다.
		jdbcTemplate.update(
			"INSERT INTO teacher_student_relationships (id, teacher_id, student_id, status,"
				+ " started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentId, now, now);

		// 🔴 내 것과 남의 것을 둘 다 넣는다. 남의 것이 없으면 "안 보인다"가 공허하다.
		problemSetId = insertProblemSet(teacherId, studentId, now);
		otherProblemSetId = insertProblemSet(teacherId, studentId, now);
	}

	@Test
	@DisplayName("🔴 전제 — 이 커넥션이 정말 RLS 대상이다")
	void restrictedConnectionIsSubjectToRowLevelSecurity() {
		assertThat(privilegeFlags(restricted))
			.as("superuser 나 BYPASSRLS 면 아래 단언이 전부 참이 되어 아무것도 증명하지 못한다")
			.isEqualTo("false/false");
	}

	@Test
	@DisplayName("🔴 전제 — 행이 실제로 있다. 관리자 눈에는 2건이 보인다")
	void fixtureRowsExist() {
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM saved_problem_sets", Integer.class))
			.as("행이 없으면 isEmpty() 단언이 RLS 와 무관하게 통과한다 — 이전 판의 결함이다")
			.isEqualTo(2);
	}

	@Test
	@DisplayName("🔴 범위를 넣지 않으면 학생 컨텍스트만으로는 못 읽는다")
	void studentContextAloneCannotRead() {
		List<String> visible = restrictedTransaction.execute(status -> {
			setStudent(studentId);
			return selectProblemSetIds();
		});

		assertThat(visible)
			.as("행이 2건 있는데도 안 보여야 한다. 소유 확인을 건너뛰고 읽히면 범위 변수가 무의미하다")
			.isEmpty();
	}

	@Test
	@DisplayName("🔴 범위만 넣고 학생 주체가 없으면 못 읽는다")
	void scopeAloneCannotRead() {
		List<String> visible = restrictedTransaction.execute(status -> {
			setScope(problemSetId);
			return selectProblemSetIds();
		});

		assertThat(visible).as("범위만으로 열리면 주체 확인이 무의미해진다").isEmpty();
	}

	@Test
	@DisplayName("🔴 둘 다 있으면 범위에 넣은 그 하나만 보인다 — 남의 것은 안 보인다")
	void scopeOpensExactlyOneProblemSet() {
		List<String> visible = restrictedTransaction.execute(status -> {
			setStudent(studentId);
			setScope(problemSetId);
			return selectProblemSetIds();
		});

		// 🔴 isNotEmpty() 가 아니라 기대한 그 값 하나인지 본다.
		//    남의 것이 섞이면 범위 변수가 그냥 우회로다.
		assertThat(visible).containsExactly(problemSetId.toString());
		assertThat(visible).doesNotContain(otherProblemSetId.toString());
	}

	@Test
	@DisplayName("🔴 항목 테이블도 같은 범위를 따른다")
	void itemsFollowTheSameScope() {
		// 🔴 항목은 problem_generation_items 를 먼저 만들어야 한다 —
		//    FK (item_id, teacher_id, problem_request_id) 가 그 테이블을 가리킨다(실측).
		//    픽스처가 제품 제약을 우회하지 않게 실제 사슬을 그대로 만든다.
		OffsetDateTime now = OffsetDateTime.now();
		UUID itemId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO problem_generation_items (id, teacher_id, problem_request_id, ordinal,"
				+ " stem, validation_status, raw_payload, created_at, updated_at)"
				+ " SELECT ?, teacher_id, problem_request_id, 1, '문항', 'PASSED', '{}'::jsonb, ?, ?"
				+ " FROM saved_problem_sets WHERE id = ?",
			itemId, now, now, problemSetId);
		jdbcTemplate.update(
			"INSERT INTO saved_problem_set_items (problem_set_id, item_id, teacher_id,"
				+ " problem_request_id, ordinal, item_snapshot)"
				+ " SELECT id, ?, teacher_id, problem_request_id, 1, '{}'::jsonb"
				+ " FROM saved_problem_sets WHERE id = ?",
			itemId, problemSetId);

		List<String> withoutScope = restrictedTransaction.execute(status -> {
			setStudent(studentId);
			return restricted.queryForList(
				"SELECT item_id::text FROM saved_problem_set_items", String.class);
		});
		assertThat(withoutScope).isEmpty();

		List<String> withScope = restrictedTransaction.execute(status -> {
			setStudent(studentId);
			setScope(problemSetId);
			return restricted.queryForList(
				"SELECT problem_set_id::text FROM saved_problem_set_items", String.class);
		});
		assertThat(withScope).containsExactly(problemSetId.toString());
	}

	@Test
	@DisplayName("🔴 범위는 트랜잭션이 끝나면 남지 않는다 — 커넥션 재사용 오염 방지")
	void scopeIsTransactionLocal() {
		restrictedTransaction.executeWithoutResult(status -> {
			setStudent(studentId);
			setScope(problemSetId);
			assertThat(selectProblemSetIds()).as("같은 트랜잭션 안에서는 보인다").hasSize(1);
		});

		List<String> afterCommit = restrictedTransaction.execute(status -> {
			setStudent(studentId);
			return selectProblemSetIds();
		});

		assertThat(afterCommit)
			.as("앞 트랜잭션의 범위가 남으면 다음 요청이 남의 문항을 본다")
			.isEmpty();
	}

	private void setStudent(UUID id) {
		restricted.queryForObject("SELECT set_config('checkon.current_student_id', ?, true)",
			String.class, id.toString());
	}

	private void setScope(UUID id) {
		restricted.queryForObject("SELECT set_config('checkon.scope_problem_set_id', ?, true)",
			String.class, id.toString());
	}

	private List<String> selectProblemSetIds() {
		return restricted.queryForList("SELECT id::text FROM saved_problem_sets", String.class);
	}

	private UUID insertAccount(String email, String role, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO accounts (id, email, role, status, created_at) VALUES (?, ?, ?, ?, ?)",
			id, email, role, "ACTIVE", now);
		return id;
	}

	/** 강사 요청 → 저장된 문항 세트 → 학생 배정까지 한 벌 만든다. */
	private UUID insertProblemSet(UUID teacherId, UUID student, OffsetDateTime now) {
		UUID requestId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO problem_generation_requests (id, teacher_id, tenant_alias, target_kind,"
				+ " student_id, target_ref, ai_idempotency_key, snapshot_hash, request_payload,"
				+ " status, requested_at, updated_at)"
				+ " VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)",
			requestId, teacherId, "tn_" + hex32(), student, "st_" + hex32(),
			"pg_" + hex32(), "sha256:" + hex32() + hex32(), now, now);

		UUID setId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO saved_problem_sets (id, teacher_id, problem_request_id, status,"
				+ " saved_at, updated_at) VALUES (?, ?, ?, 'SAVED', ?, ?)",
			setId, teacherId, requestId, now, now);

		jdbcTemplate.update(
			"INSERT INTO problem_assignments (id, teacher_id, problem_request_id, problem_set_id,"
				+ " student_id, status, published_at)"
				+ " VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?)",
			UUID.randomUUID(), teacherId, requestId, setId, student, now);
		return setId;
	}

	private static String hex32() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
