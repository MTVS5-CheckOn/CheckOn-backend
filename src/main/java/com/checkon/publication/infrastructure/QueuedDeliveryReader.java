package com.checkon.publication.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.publication.domain.QueuedDelivery;

/**
 * 승우님 원장을 <b>읽기만</b> 한다. 🔴 <b>이 클래스에 INSERT·UPDATE·DELETE 가 없다.</b>
 *
 * <p>🔴 <b>왜 쓰지 않나</b> — {@code monthly_report_deliveries.status} 를 우리가
 * {@code DELIVERED} 로 바꾸면, 나중에 승우님 코드가 같은 컬럼을 건드릴 때 누가 주인인지
 * 알 수 없게 된다. 그리고 「승우님 것에 쓰기 0건」이라는 이 작업의 깨끗한 주장이 깨진다.
 * 멱등은 <b>우리 원장 쪽</b>에서 보장한다. {@code DELIVERED} 표시 여부는 안건이다(MB-61).</p>
 *
 * <p>🔴 <b>강사 컨텍스트가 열려 있어야 한다.</b> V37 세 테이블 모두 RLS 가 켜져 있고
 * ({@code rowsecurity=true force=true}, 2026-08-27 실측) 정책이
 * {@code teacher_id = current_checkon_teacher_id()} 인 {@code FOR ALL} 하나다.
 * 컨텍스트 없이 부르면 예외가 아니라 <b>조용히 0행</b>이다.</p>
 *
 * <p>🔴 {@code teacher_profiles} 는 RLS 밖이라(실측 {@code rowsecurity=false}) 강사 목록은
 * 컨텍스트 없이 읽는다. 그래야 「어느 강사에게 대기 배달이 있는가」를 알 수 있다 —
 * 배달 테이블 자체는 강사를 이미 알아야 읽히기 때문이다.</p>
 */
@Repository
public class QueuedDeliveryReader {

	/**
	 * 🔴 {@code status = 'QUEUED' AND channel = 'PARENT_APP'} 이 발행 신호다.
	 * 두 조건 다 필요하다 — 채널이 지금은 CHECK 로 하나뿐이지만, 조건을 빼 두면 채널이
	 * 늘어나는 날 <b>다른 채널로 보낼 것까지 학부모 앱에 발행</b>한다.
	 *
	 * <p>보고서를 조인해 학생·달·AI 결과를 함께 가져온다. 🔴 배달 하나가 보고서 하나를
	 * 가리키므로({@code fk_monthly_report_delivery_report}) 조인이 행을 늘리지 않는다.</p>
	 */
	private static final String FIND_QUEUED = """
		SELECT delivery.id, delivery.report_id, delivery.teacher_id,
		       report.student_id, report.report_month, report.ai_status,
		       report.ai_payload::text
		FROM monthly_report_deliveries delivery
		JOIN monthly_reports report ON report.id = delivery.report_id
		WHERE delivery.status = 'QUEUED'
		  AND delivery.channel = 'PARENT_APP'
		ORDER BY delivery.queued_at, delivery.id
		LIMIT ?
		""";

	/** 🔴 RLS 밖이라 컨텍스트 없이 읽는다. 강사 수만큼 배치가 돈다. */
	private static final String FIND_TEACHERS = """
		SELECT id FROM teacher_profiles ORDER BY id
		""";

	private final JdbcTemplate jdbcTemplate;

	public QueuedDeliveryReader(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<UUID> findAllTeacherIds() {
		return jdbcTemplate.queryForList(FIND_TEACHERS, UUID.class);
	}

	/** 🔴 강사 컨텍스트가 열린 트랜잭션 안에서만 부른다. 밖이면 조용히 빈 목록이다. */
	public List<QueuedDelivery> findQueued(int limit) {
		return jdbcTemplate.query(FIND_QUEUED, QueuedDeliveryReader::map, limit);
	}

	private static QueuedDelivery map(ResultSet rs, int rowNum) throws SQLException {
		java.sql.Date month = rs.getDate(5);
		return new QueuedDelivery(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getObject(3, UUID.class),
			rs.getObject(4, UUID.class),
			month == null ? null : YearMonth.from(month.toLocalDate()),
			rs.getString(6),
			rs.getString(7));
	}
}
