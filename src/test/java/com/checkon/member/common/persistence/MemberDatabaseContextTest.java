package com.checkon.member.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.support.MemberPostgresSupport;

/**
 * RLS 주체 설정이 <b>트랜잭션 로컬</b>인지 본다.
 *
 * <p>커넥션은 요청이 끝나면 풀로 돌아가 다른 주체가 재사용한다. {@code set_config} 의 세 번째
 * 인자를 {@code false} 로 두면 값이 커넥션에 남아, 다음 요청이 앞 사람의 주체로 조회하게 된다.</p>
 *
 * <p>🔴 {@link MemberPostgresSupport} 를 상속해 <b>컨테이너와 스프링 컨텍스트를 공유</b>한다
 * (G17). 자기 컨테이너를 띄우면 같은 프로퍼티 조합이라도 컨텍스트 캐시가 갈려 컨테이너 수가 늘고
 * 통합 테스트 전체가 느려진다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("dev")
class MemberDatabaseContextTest extends MemberPostgresSupport {

	private static final String READ_SETTING =
		"SELECT current_setting('checkon.current_student_id', true)";

	@Autowired MemberDatabaseContext memberDatabaseContext;
	@Autowired TransactionTemplate transactionTemplate;
	@Autowired JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("설정한 트랜잭션 안에서는 값이 보인다")
	void settingIsVisibleInsideTransaction() {
		UUID studentProfileId = UUID.randomUUID();

		String seen = transactionTemplate.execute(status -> {
			memberDatabaseContext.setCurrentStudent(studentProfileId);
			return jdbcTemplate.queryForObject(READ_SETTING, String.class);
		});

		assertThat(seen).isEqualTo(studentProfileId.toString());
	}

	@Test
	@DisplayName("🔴 트랜잭션이 끝나면 값이 남지 않는다 — 커넥션 재사용 오염 방지")
	void settingIsTransactionLocal() {
		UUID studentProfileId = UUID.randomUUID();
		transactionTemplate.executeWithoutResult(status ->
			memberDatabaseContext.setCurrentStudent(studentProfileId));

		String afterCommit = transactionTemplate.execute(status ->
			jdbcTemplate.queryForObject(READ_SETTING, String.class));

		assertThat(afterCommit)
			.as("앞 트랜잭션의 주체가 남아 있으면 다음 요청이 남의 데이터를 본다")
			.isNotEqualTo(studentProfileId.toString());
	}

	@Test
	@DisplayName("트랜잭션 밖에서 부르면 조용히 넘어가지 않고 실패한다")
	void requiresActiveTransaction() {
		UUID studentProfileId = UUID.randomUUID();

		assertThatThrownBy(() -> memberDatabaseContext.setCurrentStudent(studentProfileId))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("active database transaction");
	}

	@Test
	@DisplayName("null 주체를 설정하려 하면 실패한다 — 모르는 값으로 경계를 열지 않는다")
	void rejectsNullSubject() {
		assertThatThrownBy(() ->
			transactionTemplate.executeWithoutResult(status ->
				memberDatabaseContext.setCurrentStudent(null)))
			.isInstanceOf(NullPointerException.class);
	}
}
