package com.checkon.member.common.persistence;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * member 요청의 RLS 주체를 현재 트랜잭션의 커넥션에 설정한다.
 *
 * <p>🔴 강사 테넌트 설정은 여기서 절대 건드리지 않는다. member 가 강사 경계를 열면 학생 요청이
 * 남의 반 데이터를 보게 된다 — 이 설계의 최상위 불변식이며 코드 규칙 G1 이 문자열로 막는다.</p>
 *
 * <p>커넥션은 요청이 끝나면 풀로 돌아가 다른 주체가 재사용한다. 그래서 세션 전역 SET 대신
 * 커밋·롤백 양쪽에서 PostgreSQL 이 자동 해제하는 {@code set_config(..., true)} 를 쓴다.</p>
 *
 * <p>실제 정책 검증은 대상 테이블이 생기는 PR2 에서 한다.</p>
 */
@Component
public class MemberDatabaseContext {

	public static final String ACCOUNT_SETTING = "checkon.current_account_id";
	public static final String STUDENT_SETTING = "checkon.current_student_id";
	public static final String PARENT_SETTING = "checkon.current_parent_id";

	private static final String SET_CONFIG = "SELECT set_config(?, ?, true)";

	private final JdbcTemplate jdbcTemplate;

	public MemberDatabaseContext(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void setCurrentAccount(UUID accountId) {
		apply(ACCOUNT_SETTING, accountId);
	}

	public void setCurrentStudent(UUID studentProfileId) {
		apply(STUDENT_SETTING, studentProfileId);
	}

	public void setCurrentParent(UUID parentProfileId) {
		apply(PARENT_SETTING, parentProfileId);
	}

	private void apply(String setting, UUID value) {
		Objects.requireNonNull(value, () -> setting + " must not be null");
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
				"Member database context requires an active database transaction"
			);
		}
		jdbcTemplate.queryForObject(SET_CONFIG, String.class, setting, value.toString());
	}
}
