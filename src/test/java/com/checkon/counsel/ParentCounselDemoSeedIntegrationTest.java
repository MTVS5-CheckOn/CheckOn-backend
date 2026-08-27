package com.checkon.counsel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@DisplayName("학부모 상담 로컬 데모 씨드")
class ParentCounselDemoSeedIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("Given 동일한 상담 씨드 When 두 번 실행하면 Then 고정 데모 데이터가 중복되지 않는다")
	void remainsIdempotentWhenExecutedTwice() {
		var resource = new FileSystemResource("scripts/demo/parent-counsel/seed.sql");
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			ScriptUtils.executeSqlScript(connection, resource);
			ScriptUtils.executeSqlScript(connection, resource);
			return null;
		});

		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM counsel_inquiries
			WHERE teacher_id = '0198f000-0000-7000-8000-000000000001'::uuid
			  AND inquiry_ref LIKE 'demo-counsel-%'
			""", Integer.class)).isEqualTo(6);
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM counsel_draft_jobs
			WHERE teacher_id = '0198f000-0000-7000-8000-000000000001'::uuid
			  AND inquiry_ref LIKE 'demo-counsel-%'
			""", Integer.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM guardian_labels
			WHERE teacher_id = '0198f000-0000-7000-8000-000000000001'::uuid
			  AND parent_id = '0198f000-0000-7000-8000-000000000010'::uuid
			""", Integer.class)).isEqualTo(2);
	}
}
