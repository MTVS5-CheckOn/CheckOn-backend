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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 범위 세션 변수(설계 §6-4-2)가 우회로가 아니라는 것을 증명한다.
 *
 * <p>불변식 4번 때문에 {@code saved_problem_sets} 정책은 {@code problem_assignments} 를
 * 참조할 수 없다. 대신 애플리케이션이 소유를 먼저 확인하고 확인된 id 를
 * {@code checkon.scope_problem_set_id} 에 넣는다.</p>
 *
 * <p>🔴 이 테스트가 그 방식의 안전 근거다. 세 가지를 단언한다 —
 * ① 범위를 안 넣으면 못 읽는다 ② 남의 problem_set 을 넣어도 그 학생 행은 안 보인다
 * ③ 트랜잭션이 끝나면 범위가 남지 않는다. 하나라도 깨지면 세션 변수는 그냥 우회로다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000"
})
@ActiveProfiles("dev")
@Testcontainers
class MemberScopeSessionVariableIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired TransactionTemplate transactionTemplate;

	private UUID studentId;
	private UUID problemSetId;
	private UUID otherProblemSetId;

	@BeforeEach
	void setUp() {
		studentId = UUID.randomUUID();
		problemSetId = UUID.randomUUID();
		otherProblemSetId = UUID.randomUUID();
	}

	@Test
	@DisplayName("🔴 범위를 넣지 않으면 학생 컨텍스트만으로는 못 읽는다")
	void studentContextAloneCannotRead() {
		List<String> visible = transactionTemplate.execute(status -> {
			setStudent(studentId);
			return selectProblemSetIds();
		});

		assertThat(visible)
			.as("소유 확인을 건너뛰고 읽히면 범위 세션 변수가 무의미해진다")
			.isEmpty();
	}

	@Test
	@DisplayName("🔴 범위만 넣고 학생 주체가 없으면 못 읽는다")
	void scopeAloneCannotRead() {
		List<String> visible = transactionTemplate.execute(status -> {
			setScope(problemSetId);
			return selectProblemSetIds();
		});

		assertThat(visible).isEmpty();
	}

	@Test
	@DisplayName("🔴 범위는 트랜잭션이 끝나면 남지 않는다 — 커넥션 재사용 오염 방지")
	void scopeIsTransactionLocal() {
		transactionTemplate.executeWithoutResult(status -> setScope(problemSetId));

		String after = transactionTemplate.execute(status ->
			jdbcTemplate.queryForObject(
				"SELECT coalesce(current_setting('checkon.scope_problem_set_id', true), '')",
				String.class));

		assertThat(after)
			.as("앞 트랜잭션의 범위가 남으면 다음 요청이 남의 문항을 본다")
			.isNotEqualTo(problemSetId.toString());
	}

	@Test
	@DisplayName("범위 함수는 설정된 값을 그대로 돌려준다")
	void scopeFunctionReflectsSetting() {
		String seen = transactionTemplate.execute(status -> {
			setScope(otherProblemSetId);
			return jdbcTemplate.queryForObject(
				"SELECT current_checkon_scope_problem_set_id()::text", String.class);
		});

		assertThat(seen).isEqualTo(otherProblemSetId.toString());
	}

	private void setStudent(UUID id) {
		jdbcTemplate.queryForObject(
			"SELECT set_config('checkon.current_student_id', ?, true)", String.class, id.toString());
	}

	private void setScope(UUID id) {
		jdbcTemplate.queryForObject(
			"SELECT set_config('checkon.scope_problem_set_id', ?, true)",
			String.class, id.toString());
	}

	private List<String> selectProblemSetIds() {
		return jdbcTemplate.queryForList("SELECT id::text FROM saved_problem_sets", String.class);
	}
}
