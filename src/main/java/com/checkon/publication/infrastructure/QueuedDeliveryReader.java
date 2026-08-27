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
 * <p>🔴 <b>예외가 하나 있다</b> — 발행에 성공한 배달을 {@code DELIVERED} 로 표시하는
 * {@link #markDelivered}. 실측으로 주인이 없음을 확인하고 넣었다(MB-61):</p>
 * <ul>
 *   <li>{@code MonthlyReportRepository:180-186} 의 INSERT 가 {@code status} 를 넣지 않는다 —
 *       승우님 코드는 {@code QUEUED} 만 만든다(컬럼 DEFAULT)</li>
 *   <li>{@code DELIVERED}·{@code delivered_at} 을 쓰는 코드가 publication 밖에 <b>0곳</b>이다</li>
 *   <li>유일한 소비처 {@code MonthlyReportService} 의 {@code mutable()} 이
 *       {@code !"NOT_SENT".equals(deliveryStatus)} 로 판정한다 — <b>QUEUED 와 DELIVERED 를
 *       똑같이 취급</b>하므로 바꿔도 강사 쪽 동작이 변하지 않는다. 그 예외 메시지가
 *       {@code "A queued or delivered report is read-only"} 다 — 🔴 <b>DELIVERED 를 설계에
 *       넣어 두셨는데 아무도 안 채운 것</b>이다</li>
 * </ul>
 * <p>🔴 그 밖에는 여전히 읽기만 한다. 특히 <b>실패를 {@code FAILED} 로 바꾸지 않는다</b> —
 * 재시도 가능해야 한다.</p>
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
	private static final String SELECT_COLUMNS = """
		SELECT delivery.id, delivery.report_id, delivery.teacher_id,
		       report.student_id, report.report_month, report.ai_status,
		       report.ai_payload::text
		FROM monthly_report_deliveries delivery
		JOIN monthly_reports report ON report.id = delivery.report_id
		WHERE delivery.status = 'QUEUED'
		  AND delivery.channel = 'PARENT_APP'
		""";

	/**
	 * 🔴 <b>{@code FOR UPDATE OF delivery SKIP LOCKED}</b> — 다중 인스턴스에서 같은 배달을
	 * 둘이 집는 것을 DB 가 막는다(MB-64). 잠긴 행은 <b>건너뛴다</b>, 기다리지 않는다 —
	 * 기다리면 한 인스턴스가 느릴 때 나머지가 전부 멈춘다.
	 *
	 * <p>🔴 {@code OF delivery} 를 붙여 <b>배달 행만</b> 잠근다. 안 붙이면 조인한
	 * {@code monthly_reports} 행까지 잠겨 강사가 그 보고서를 못 고친다.</p>
	 *
	 * <p>🔴 <b>한 건씩 집는다.</b> 잠금은 트랜잭션이 끝나면 풀리므로, 잠금이 뜻을 가지려면
	 * <b>집는 것과 쓰는 것이 같은 트랜잭션</b>이어야 한다. 「한 배달 = 한 트랜잭션」과
	 * 「잠금이 발행까지 유지된다」를 둘 다 지키는 방법이 이것뿐이다.</p>
	 */
	private static final String LOCK_NEXT_QUEUED = SELECT_COLUMNS + """
		ORDER BY delivery.queued_at, delivery.id
		FOR UPDATE OF delivery SKIP LOCKED
		LIMIT 1
		""";

	/** 잠금 없이 남은 수만 센다. 🔴 다른 인스턴스가 잠근 행도 세므로 <b>추정치</b>다. */
	private static final String COUNT_QUEUED = SELECT_COLUMNS + """
		LIMIT ?
		""";

	/**
	 * 🔴 발행에 성공한 배달만 표시한다. {@code status='QUEUED'} 조건을 걸어 <b>이미 표시된
	 * 행을 두 번 건드리지 않는다.</b>
	 *
	 * <p>🔴 V37 의 {@code ck_monthly_report_delivery_completion} 이
	 * {@code DELIVERED ⟹ delivered_at NOT NULL AND failure_code NULL} 을 요구한다 —
	 * {@code delivered_at} 을 같이 채운다. {@code failure_code} 는 QUEUED 였으므로 이미 NULL 이다.</p>
	 */
	private static final String MARK_DELIVERED = """
		UPDATE monthly_report_deliveries
		SET status = 'DELIVERED', delivered_at = ?
		WHERE id = ? AND status = 'QUEUED'
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

	/**
	 * 다음 배달 한 건을 <b>잠그고</b> 가져온다. 🔴 강사 컨텍스트가 열린 <b>쓰기</b> 트랜잭션
	 * 안에서만 부른다 — 밖이면 조용히 빈 결과이고, 읽기 전용이면 {@code FOR UPDATE} 가 죽는다.
	 */
	public java.util.Optional<QueuedDelivery> lockNextQueued() {
		return jdbcTemplate.query(LOCK_NEXT_QUEUED, QueuedDeliveryReader::map).stream().findFirst();
	}

	/** 🔴 잠금 없이 센다. 상한에 걸려 남긴 수를 로그에 찍기 위한 <b>추정치</b>다. */
	public int countQueued(int limit) {
		return jdbcTemplate.query(COUNT_QUEUED, QueuedDeliveryReader::map, limit).size();
	}

	/**
	 * 🔴 <b>발행 성공 후</b> 같은 트랜잭션에서 부른다. 실패했으면 부르지 않는다 —
	 * {@code QUEUED} 로 남아야 다음 회차에 재시도된다.
	 *
	 * @return 표시된 행 수. 0이면 다른 인스턴스가 먼저 처리한 것이다
	 */
	public int markDelivered(UUID deliveryId, java.time.Instant deliveredAt) {
		return jdbcTemplate.update(MARK_DELIVERED,
			java.time.OffsetDateTime.ofInstant(deliveredAt, java.time.ZoneOffset.UTC),
			deliveryId);
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
