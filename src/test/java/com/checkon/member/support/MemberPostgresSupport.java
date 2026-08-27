package com.checkon.member.support;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * member 통합 테스트가 <b>하나의</b> PostgreSQL 컨테이너를 공유한다.
 *
 * <p>🔴 왜 공유하나 — 클래스마다 컨테이너를 띄웠더니 Docker 가 고갈돼 승우님 테스트 4개가
 * {@code Could not connect to Ryuk} · {@code Can't get Docker image} 로 죽었다(실측).
 * <b>내 테스트가 남의 테스트를 깨뜨린 것</b>이라 개수를 줄이는 게 맞다.</p>
 *
 * <p>🔴 왜 {@code @Testcontainers} · {@code @Container} 를 쓰지 않나 —
 * 그 확장은 <b>테스트 클래스가 끝나면 컨테이너를 멈춘다.</b> 정적 필드를 여러 클래스가 공유하면
 * <b>먼저 끝난 클래스가 뒤 클래스의 컨테이너를 죽인다</b> — 실측으로
 * {@code Connection is not available ... total=0} 이 19건 났고, 원인을 커넥션 고갈로 오진했다.
 * ({@code max_connections} 는 300 이었고 실제 사용은 12 였다.)
 * 그래서 수명을 JVM 에 맡긴다 — 정적 초기화로 한 번 시작하고, 정리는 Ryuk 이 종료 시 한다.</p>
 *
 * <p>🔴 상태를 공유하므로 <b>하위 클래스는 각자 {@code @BeforeEach} 에서 자기 픽스처를 지운다.</b>
 * 지우지 않으면 실행 순서에 따라 결과가 달라진다.</p>
 *
 * <p>🔴 하위 클래스는 {@code spring.datasource.hikari.maximum-pool-size} 를 작게 둔다.
 * Spring 컨텍스트마다 풀이 하나씩 붙고, 캐시된 옛 컨텍스트도 커넥션을 계속 붙잡는다.</p>
 */
public abstract class MemberPostgresSupport {

	private static final String RESTRICTED_ROLE = "member_rls_probe";
	private static final String RESTRICTED_PASSWORD = "member_rls_probe_pw";

	@ServiceConnection
	public static final PostgreSQLContainer POSTGRES =
		new PostgreSQLContainer("postgres:18.4");

	static {
		POSTGRES.start();
	}

	/**
	 * 🔴 RLS 가 <b>실제로 걸리는</b> 커넥션. 컨테이너 기본 사용자는
	 * {@code super=true bypassrls=true} 라 정책이 통째로 우회된다(MB-34).
	 * 그 역할로 쓴 RLS 단언은 아무것도 증명하지 않는다.
	 *
	 * <p>🔴 이 커넥션을 쓰는 테스트는 <b>「전제」 단언을 먼저 둔다</b> —
	 * {@code rolsuper/rolbypassrls} 가 {@code false/false} 인지. 그 단언이 없으면
	 * 나중에 역할 설정이 깨져도 테스트는 계속 초록불이다.
	 *
	 * @param admin 관리자 커넥션. 역할 생성·GRANT 에만 쓴다
	 */
	protected static JdbcTemplate restrictedJdbcTemplate(JdbcTemplate admin) {
		Integer exists = admin.queryForObject(
			"SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, RESTRICTED_ROLE);
		if (exists == null || exists == 0) {
			admin.execute("CREATE ROLE " + RESTRICTED_ROLE
				+ " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS"
				+ " PASSWORD '" + RESTRICTED_PASSWORD + "'");
		}
		admin.execute("GRANT USAGE ON SCHEMA public TO " + RESTRICTED_ROLE);
		admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
			+ RESTRICTED_ROLE);
		admin.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RESTRICTED_ROLE);

		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(RESTRICTED_ROLE);
		dataSource.setPassword(RESTRICTED_PASSWORD);
		return new JdbcTemplate(dataSource);
	}

	/**
	 * 🔴 member 픽스처 정리. <b>자식 → 부모 순서가 고정</b>이라 각 클래스가 따로 적으면 갈린다.
	 *
	 * <p>실제로 세 번 깨졌다 — {@code member_student_public_ids} 누락 ·
	 * {@code problem_generation_items} 누락 · {@code parent_profiles} 누락.
	 * 전부 「내 클래스 단독으로는 통과하는데 전체 실행에서 죽는다」로 나타났다.
	 * 컨테이너를 공유하므로 <b>앞 클래스가 남긴 행까지</b> 지워야 하고, 그러려면 목록이
	 * 한 곳에 있어야 한다.</p>
	 *
	 * <p>🔴 FK 가 {@code RESTRICT} 라 순서를 어기면 조용히 넘어가지 않고 정리 자체가 실패한다.</p>
	 */
	public static void clearMemberFixtures(JdbcTemplate admin) {
		String[] ordered = {
			"authentication_sessions",
			// 🔴 V42 월별 집계 계열 — 자식 → 부모 순서. teacher_profiles·student_profiles 를 참조하므로
			//    두 profile 앞에 있어야 한다. clear 는 학생 컨텍스트가 없어 RLS 로 안 지워질 것 같지만
			//    admin 커넥션은 superuser 라 통과한다(MB-34).
			"member_metric_refresh_outbox",
			"member_monthly_weakness_metrics",
			"member_monthly_student_metrics",
			// 🔴 V41 알림·질문 계열 — 자식 → 부모 순서. member_notifications 는 accounts 만 참조,
			//    member_question_messages 는 member_questions 를 참조, member_questions 는
			//    student/teacher/assignment/member_attempts 를 참조한다.
			"member_notifications",
			"member_notification_preferences",
			"member_question_messages",
			"member_questions",
			// 🔴 V40 attempt 계열 — 자식 → 부모 순서. member_attempts 가 problem_assignments·
			//    student_profiles·teacher_profiles 를 참조하므로 그 앞에 있어야 정리가 통과한다.
			"member_learning_sessions",
			"member_attempt_answers",
			"member_attempt_events",
			"member_attempt_items",
			"member_attempts",
			"problem_assignments",
			"saved_problem_set_items",
			"saved_problem_sets",
			"problem_generation_items",
			"problem_generation_requests",
			"member_student_activation",
			"member_display_names",
			"member_student_public_ids",
			// 🔴 PR4 가 추가했다. claims 가 codes 를 참조하므로 자식이 먼저다.
			"member_idempotency_records",
			"member_invitation_claims",
			"member_invitation_codes",
			"teacher_student_relationships",
			"parent_student_relationships",
			"parent_teacher_relationships",
			"student_profiles",
			"parent_profiles",
			"teacher_profiles",
			"account_password_credentials",
			"accounts",
		};
		for (String table : ordered) {
			admin.update("DELETE FROM " + table);
		}
	}

	/** 🔴 {@code false/false} 여야 한다. 아니면 그 클래스의 RLS 단언은 전부 무의미하다. */
	protected static String privilegeFlags(JdbcTemplate restricted) {
		return restricted.queryForObject(
			"SELECT rolsuper||'/'||rolbypassrls FROM pg_roles WHERE rolname = current_user",
			String.class);
	}

	/**
	 * 🔴 {@code student_profiles} 한 행. member 통합 테스트가 <b>여러 지점</b>에서 학생을 만들어야
	 * 하므로 헬퍼를 여기 둔다.
	 *
	 * <p>왜 여기냐 — 이 클래스는 모든 member 통합 테스트가 상속하는 <b>단일 지점</b>이다.
	 * {@code MembershipRlsEnforcedSupport.StudentFixture} 는 {@code protected record} 라
	 * 다른 패키지 서브클래스에서 생성자가 막힌다(JLS 6.6.2). 그렇다고 두 곳에서 같은 INSERT 를
	 * 사본으로 유지하면 컬럼 하나만 바뀌어도 갈린다.</p>
	 *
	 * <p>🔴 이 헬퍼는 {@code student_profiles} 만 만든다 — {@code member_display_names} ·
	 * {@code member_student_public_ids} · {@code member_student_activation} 은 픽스처를 요구하는
	 * 상위 헬퍼가 따로 붙인다. 그래야 이 attempt·worksheet 처럼 profile 만 필요한 테스트가 규약을
	 * 다 만족시키지 않아도 된다.</p>
	 */
	public static UUID insertStudentProfile(
		JdbcTemplate admin, UUID accountId, String alias, Integer grade, OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO student_profiles (id, account_id, alias, grade,"
			+ " account_linked_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			id, accountId, alias, grade, now, now, now);
		return id;
	}
}
