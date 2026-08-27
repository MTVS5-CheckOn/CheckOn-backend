package com.checkon.member.common.persistence;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * member 소유 테이블의 RLS 상태를 기동 시 확인한다.
 *
 * <p>기존 {@code TenantDatabaseRoleSafetyVerifier} 는 보호 테이블을 이름으로 15개 고정해 두어
 * 신규 {@code member_*} 테이블을 전혀 검사하지 않는다(설계 §1-3). 그 파일을 건드리지 않으려고
 * member 전용 검증기를 따로 둔다.</p>
 *
 * <p>🔴 두 방향을 함께 본다. 필수 목록이 꺼져 있어도 실패하지만, <b>일부러 끈 목록이 켜져 있어도
 * 실패</b>한다. 후자가 더 중요하다 — 누군가 "왜 여기만 RLS 가 없지?" 하고 켜면 자녀 등록과
 * 초대코드 검증이 조용히 죽는데, 그걸 기능 장애가 아니라 기동 실패로 만든다.</p>
 */
@Component
public class MemberDatabaseRoleSafetyVerifier implements ApplicationRunner {

	/** RLS 가 반드시 켜져 있어야 하는 테이블. 소유자가 곧 조회 주체다. */
	private static final List<String> RLS_REQUIRED = List.of(
		"member_student_activation",
		"member_invitation_claims",
		"member_idempotency_records",
		// V40 · attempt 계열 — 학생 self · 학부모/강사 scope 로 격리한다.
		"member_attempts",
		"member_attempt_items",
		"member_attempt_answers",
		"member_attempt_events",
		"member_learning_sessions",
		// V41 · 질문·프로필·알림
		"member_notification_preferences",
		"member_questions",
		"member_question_messages",
		"member_notifications",
		// V42 · 월별 집계 · 재계산 outbox
		"member_monthly_student_metrics",
		"member_monthly_weakness_metrics",
		"member_metric_refresh_outbox",
		// V44 · 학부모 상담 원장과 발행 메시지
		"member_consultations",
		"member_consultation_messages",
		// V45 · 월별 보고서 발행 스냅샷 · PDF 메타 · 발행 알림 outbox
		"member_published_reports",
		"member_published_report_sections",
		"member_report_files",
		"member_report_publication_outbox"
	);

	/**
	 * 🔴 일부러 RLS 를 걸지 않은 테이블. 귀찮아서가 아니라 <b>조회 주체가 소유자가 아니어서</b>다.
	 * 근거와 대안은 {@code docs/MEMBER_OPEN_ITEMS.md} 의 MB-30 에 있다.
	 */
	private static final List<String> RLS_INTENTIONALLY_ABSENT = List.of(
		// 학부모가 자녀의 공개 ID 를 찾는다 — 자기 것이 아닌 행을 읽어야 한다
		"member_student_public_ids",
		// 학생·학부모가 강사의 초대 코드를 찾는다 — 소유자는 강사다
		"member_invitation_codes"
	);

	private static final String ROW_SECURITY_QUERY = """
		SELECT c.relname
		FROM pg_class c
		JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE n.nspname = 'public'
		  AND c.relkind = 'r'
		  AND c.relname = ANY (string_to_array(?, ','))
		  AND (c.relrowsecurity = %s OR c.relforcerowsecurity = %s)
		ORDER BY c.relname
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberDatabaseRoleSafetyVerifier(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		List<String> unprotected = query(ROW_SECURITY_QUERY.formatted("false", "false"),
			RLS_REQUIRED);
		if (!unprotected.isEmpty()) {
			throw new IllegalStateException(
				"member tables must ENABLE and FORCE row level security: " + unprotected
			);
		}

		List<String> unexpectedlyProtected = query(ROW_SECURITY_QUERY.formatted("true", "true"),
			RLS_INTENTIONALLY_ABSENT);
		if (!unexpectedlyProtected.isEmpty()) {
			throw new IllegalStateException(
				"row level security was enabled on tables that must stay open because the reader "
					+ "is not the owner (see MB-30): " + unexpectedlyProtected
			);
		}
	}

	/**
	 * 🔴 목록을 varargs 로 펼치면 파라미터 수가 안 맞는다. 쉼표로 이어 한 개로 넘기고
	 * PostgreSQL 쪽에서 배열로 되돌린다 — 배열 타입 매핑에 기대지 않는다.
	 */
	private List<String> query(String sql, List<String> tables) {
		return jdbcTemplate.queryForList(sql, String.class, String.join(",", tables));
	}
}
