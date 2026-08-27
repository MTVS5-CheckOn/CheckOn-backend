package com.checkon.member.analytics.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.analytics.domain.MonthlyStudentMetric;
import com.checkon.member.analytics.domain.MonthlyWeaknessMetric;

/**
 * {@code member_monthly_student_metrics} · {@code member_monthly_weakness_metrics} 저장·조회.
 *
 * <p>🔴 UPSERT 로 <b>같은 (teacher_id, student_id, month[, area, type])</b> 를 원자적으로 갱신한다 —
 * 재계산이 멱등이어야 한다. {@code calculated_at}·{@code calculation_version}·{@code month_zone}
 * 은 매번 최신값으로 덮어쓴다.</p>
 *
 * <p>🔴 <b>{@code accuracy_rate} 컬럼을 만들지 않았다</b> — 비율은 읽는 쪽에서
 * {@code correct_count / scored_count} 로 계산한다. 값과 유도식이 두 곳에 있으면 갈라진다.</p>
 */
@Repository
public class MemberMonthlyMetricsRepository {

	private static final String STUDENT_COLS =
		"teacher_id, student_id, month, month_zone, scored_count, correct_count,"
			+ " total_active_sec, calculation_version, calculated_at";

	private static final String UPSERT_STUDENT = """
		INSERT INTO member_monthly_student_metrics
			(teacher_id, student_id, month, month_zone, scored_count, correct_count,
			 total_active_sec, calculation_version, calculated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (teacher_id, student_id, month) DO UPDATE SET
			scored_count = EXCLUDED.scored_count,
			correct_count = EXCLUDED.correct_count,
			total_active_sec = EXCLUDED.total_active_sec,
			calculation_version = EXCLUDED.calculation_version,
			calculated_at = EXCLUDED.calculated_at,
			month_zone = EXCLUDED.month_zone
		""";

	private static final String UPSERT_WEAKNESS = """
		INSERT INTO member_monthly_weakness_metrics
			(teacher_id, student_id, month, month_zone, area_tag, type_tag,
			 scored_count, correct_count, status, calculation_version, calculated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (teacher_id, student_id, month, area_tag, type_tag) DO UPDATE SET
			scored_count = EXCLUDED.scored_count,
			correct_count = EXCLUDED.correct_count,
			status = EXCLUDED.status,
			calculation_version = EXCLUDED.calculation_version,
			calculated_at = EXCLUDED.calculated_at,
			month_zone = EXCLUDED.month_zone
		""";

	private static final String FIND_STUDENT_BY_MONTH = """
		SELECT %s FROM member_monthly_student_metrics
		WHERE student_id = ? AND month = ?
		""".formatted(STUDENT_COLS);

	// 🔴 학부모 경로: teacherId 필터가 있으면 해당 셀만, 없으면 전체 강사 셀을 합쳐 학생 총계를 낸다.
	//    「학생 총계 = teacher_id 다른 여러 행의 합」이다(계약 §3-3 참조).
	private static final String FIND_STUDENT_MONTH_TEACHER = FIND_STUDENT_BY_MONTH
		+ " AND teacher_id = ?";

	// 🔴 trend 조회: [fromMonth, toMonth] 양끝 포함. month 컬럼이 'YYYY-MM' 문자열이라
	//    사전순 비교가 시간순과 같다. 정렬은 읽는 쪽에서.
	private static final String FIND_STUDENT_MONTH_RANGE = """
		SELECT %s FROM member_monthly_student_metrics
		WHERE student_id = ? AND month >= ? AND month <= ?
		""".formatted(STUDENT_COLS);

	private static final String WEAKNESS_COLS =
		"teacher_id, student_id, month, month_zone, area_tag, type_tag,"
			+ " scored_count, correct_count, status, calculation_version, calculated_at";

	private static final String FIND_WEAKNESS_MONTH = """
		SELECT %s FROM member_monthly_weakness_metrics
		WHERE student_id = ? AND month = ?
		""".formatted(WEAKNESS_COLS);

	private static final String FIND_WEAKNESS_CELL = FIND_WEAKNESS_MONTH
		+ " AND area_tag = ? AND type_tag = ?";

	// 🔴 개선도 산식이 요구하는 「같은 셀」의 전월 조회. 셀은 (teacher_id, area_tag, type_tag) 고정.
	private static final String FIND_WEAKNESS_CELL_ALL_TEACHERS = """
		SELECT %s FROM member_monthly_weakness_metrics
		WHERE student_id = ? AND month = ? AND area_tag = ? AND type_tag = ?
		""".formatted(WEAKNESS_COLS);

	private final JdbcTemplate jdbcTemplate;

	public MemberMonthlyMetricsRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void upsertStudent(MonthlyStudentMetric metric) {
		jdbcTemplate.update(UPSERT_STUDENT,
			metric.teacherId(), metric.studentId(), metric.month(), metric.monthZone(),
			metric.scoredCount(), metric.correctCount(), metric.totalActiveSec(),
			metric.calculationVersion(),
			OffsetDateTime.ofInstant(metric.calculatedAt(), ZoneOffset.UTC));
	}

	public void upsertWeakness(MonthlyWeaknessMetric metric) {
		jdbcTemplate.update(UPSERT_WEAKNESS,
			metric.teacherId(), metric.studentId(), metric.month(), metric.monthZone(),
			metric.areaTag(), metric.typeTag(),
			metric.scoredCount(), metric.correctCount(), metric.status(),
			metric.calculationVersion(),
			OffsetDateTime.ofInstant(metric.calculatedAt(), ZoneOffset.UTC));
	}

	public List<MonthlyStudentMetric> findStudentByMonth(UUID studentId, String month) {
		return jdbcTemplate.query(FIND_STUDENT_BY_MONTH,
			(rs, rowNum) -> mapStudent(rs), studentId, month);
	}

	public Optional<MonthlyStudentMetric> findStudentByMonthAndTeacher(
		UUID studentId, String month, UUID teacherId
	) {
		return jdbcTemplate.query(FIND_STUDENT_MONTH_TEACHER,
			(rs, rowNum) -> mapStudent(rs), studentId, month, teacherId)
			.stream().findFirst();
	}

	public List<MonthlyStudentMetric> findStudentByMonthRange(
		UUID studentId, String fromMonth, String toMonth
	) {
		return jdbcTemplate.query(FIND_STUDENT_MONTH_RANGE,
			(rs, rowNum) -> mapStudent(rs), studentId, fromMonth, toMonth);
	}

	public List<MonthlyWeaknessMetric> findWeaknessByMonth(UUID studentId, String month) {
		return jdbcTemplate.query(FIND_WEAKNESS_MONTH,
			(rs, rowNum) -> mapWeakness(rs), studentId, month);
	}

	public List<MonthlyWeaknessMetric> findWeaknessCell(
		UUID studentId, String month, String areaTag, String typeTag
	) {
		return jdbcTemplate.query(FIND_WEAKNESS_CELL_ALL_TEACHERS,
			(rs, rowNum) -> mapWeakness(rs), studentId, month, areaTag, typeTag);
	}

	public List<MonthlyWeaknessMetric> findWeaknessByCellForTeacher(
		UUID studentId, String month, UUID teacherId, String areaTag, String typeTag
	) {
		return jdbcTemplate.query(FIND_WEAKNESS_CELL + " AND teacher_id = ?",
			(rs, rowNum) -> mapWeakness(rs),
			studentId, month, areaTag, typeTag, teacherId);
	}

	private static MonthlyStudentMetric mapStudent(java.sql.ResultSet rs)
		throws java.sql.SQLException {
		return new MonthlyStudentMetric(
			rs.getObject("teacher_id", UUID.class),
			rs.getObject("student_id", UUID.class),
			rs.getString("month"),
			rs.getString("month_zone"),
			rs.getInt("scored_count"),
			rs.getInt("correct_count"),
			rs.getInt("total_active_sec"),
			rs.getString("calculation_version"),
			rs.getObject("calculated_at", OffsetDateTime.class).toInstant()
		);
	}

	private static MonthlyWeaknessMetric mapWeakness(java.sql.ResultSet rs)
		throws java.sql.SQLException {
		return new MonthlyWeaknessMetric(
			rs.getObject("teacher_id", UUID.class),
			rs.getObject("student_id", UUID.class),
			rs.getString("month"),
			rs.getString("month_zone"),
			rs.getString("area_tag"),
			rs.getString("type_tag"),
			rs.getInt("scored_count"),
			rs.getInt("correct_count"),
			rs.getString("status"),
			rs.getString("calculation_version"),
			rs.getObject("calculated_at", OffsetDateTime.class).toInstant()
		);
	}
}
