package com.checkon.global.persistence;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 현재 강사의 식별자를 PostgreSQL RLS가 읽는 트랜잭션 로컬 설정에 전달한다.
 *
 * <p>강사는 CheckOn의 테넌트 경계다. Repository의 명시적인 teacherId 조건은
 * 애플리케이션 흐름을 이해하기 쉽게 만들고, RLS는 WHERE 조건이 빠진 직접 SQL까지
 * 차단한다. 어느 한쪽도 다른 쪽을 대신하지 않는다.</p>
 */
@Component
public class TeacherTenantDatabaseContext {

	public static final String SETTING_NAME = "checkon.current_teacher_id";

	private final JdbcTemplate jdbcTemplate;

	public TeacherTenantDatabaseContext(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * 이미 시작된 Spring 트랜잭션의 실제 DB 커넥션에 강사 경계를 설정한다.
	 *
	 * <p>커넥션은 요청 종료 후 Pool로 반환되어 다른 강사가 재사용할 수 있다.
	 * 따라서 세션 전역 SET과 수동 RESET 대신, 커밋과 롤백 모두에서 PostgreSQL이
	 * 자동 해제하는 {@code set_config(..., true)}를 사용한다.</p>
	 */
	public void setCurrentTeacher(UUID teacherId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
				"Teacher tenant context requires an active database transaction"
			);
		}
		jdbcTemplate.queryForObject(
			"SELECT set_config(?, ?, true)",
			String.class,
			SETTING_NAME,
			teacherId.toString()
		);
	}
}
