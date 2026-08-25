package com.checkon.member.common.persistence;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * member 소유 테이블이 RLS 없이 열려 있지 않은지 기동 시 확인한다.
 *
 * <p>기존 {@code TenantDatabaseRoleSafetyVerifier} 는 보호 테이블 15개를 이름으로 고정해 두어
 * 신규 {@code member_*} 테이블을 전혀 검사하지 않는다(설계 §1-3). 그 파일을 건드리지 않으려고
 * member 전용 검증기를 따로 둔다.</p>
 *
 * <p>이름 목록을 고정하지 않고 {@code member_} 접두를 스캔한다 — PR2~PR9 가 테이블을 더할 때마다
 * 이 파일을 고쳐야 하는 구조를 만들지 않기 위해서다. 아직 member 테이블이 0개라 지금은 통과한다.</p>
 */
@Component
public class MemberDatabaseRoleSafetyVerifier implements ApplicationRunner {

	private static final String UNPROTECTED_QUERY = """
		SELECT c.relname
		FROM pg_class c
		JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE n.nspname = 'public'
		  AND c.relkind = 'r'
		  AND c.relname LIKE 'member\\_%'
		  AND (c.relrowsecurity = false OR c.relforcerowsecurity = false)
		ORDER BY c.relname
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberDatabaseRoleSafetyVerifier(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		List<String> unprotected = jdbcTemplate.queryForList(UNPROTECTED_QUERY, String.class);
		if (!unprotected.isEmpty()) {
			throw new IllegalStateException(
				"member tables without ENABLE/FORCE row level security: " + unprotected
			);
		}
	}
}
