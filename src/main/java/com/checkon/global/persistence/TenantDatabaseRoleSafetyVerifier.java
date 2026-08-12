package com.checkon.global.persistence;

import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 DB 역할이 PostgreSQL RLS를 우회하지 않는지 시작 시 검증한다.
 *
 * <p>{@code FORCE ROW LEVEL SECURITY}는 테이블 소유자 우회를 막지만 슈퍼유저와
 * {@code BYPASSRLS} 역할까지 막지는 못한다. 잘못된 운영 자격 증명으로 서비스가
 * 조용히 실행되는 것보다 시작을 거절하는 편이 안전하다.</p>
 */
@Component
@ConditionalOnProperty(
	prefix = "checkon.tenant",
	name = "verify-database-role",
	havingValue = "true",
	matchIfMissing = true
)
public class TenantDatabaseRoleSafetyVerifier implements ApplicationRunner {

	private static final int RLS_TABLE_COUNT = 11;

	private final JdbcTemplate jdbcTemplate;

	public TenantDatabaseRoleSafetyVerifier(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		Map<String, Object> role = jdbcTemplate.queryForMap("""
			SELECT current_user AS role_name, rolsuper, rolbypassrls
			FROM pg_roles
			WHERE rolname = current_user
			""");
		if (Boolean.TRUE.equals(role.get("rolsuper"))
			|| Boolean.TRUE.equals(role.get("rolbypassrls"))) {
			throw new IllegalStateException(
				"Application database role must not be superuser or BYPASSRLS"
			);
		}

		Integer protectedTables = jdbcTemplate.queryForObject("""
			SELECT count(*)
			FROM pg_class table_metadata
			JOIN pg_namespace schema_metadata
			  ON schema_metadata.oid = table_metadata.relnamespace
			WHERE schema_metadata.nspname = 'public'
			  AND table_metadata.relname IN (
			    'class_groups',
			    'teacher_student_relationships',
			    'class_enrollments',
			    'detection_runs',
			    'detection_request_attempts',
			    'detection_signal_results',
			    'detection_result_evidence',
			    'ai_class_aliases',
			    'problem_generation_requests',
			    'problem_generation_outbox',
			    'problem_generation_consumed_events'
			  )
			  AND table_metadata.relrowsecurity
			  AND table_metadata.relforcerowsecurity
			""", Integer.class);
		if (protectedTables == null || protectedTables != RLS_TABLE_COUNT) {
			throw new IllegalStateException(
				"All teacher-owned tables must enable and force row-level security"
			);
		}
	}
}
