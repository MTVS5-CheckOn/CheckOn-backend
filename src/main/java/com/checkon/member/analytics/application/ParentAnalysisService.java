package com.checkon.member.analytics.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.analytics.application.dto.AnalysisResponse;
import com.checkon.member.analytics.application.dto.AnalysisResponse.Improvement;
import com.checkon.member.analytics.application.dto.AnalysisResponse.Overall;
import com.checkon.member.analytics.application.dto.AnalysisResponse.WeaknessCellPayload;
import com.checkon.member.analytics.domain.MonthlyStudentMetric;
import com.checkon.member.analytics.domain.MonthlyWeaknessMetric;
import com.checkon.member.analytics.domain.WeaknessImprovement;
import com.checkon.member.analytics.domain.WeaknessImprovement.Cell;
import com.checkon.member.analytics.infrastructure.persistence.MemberMonthlyMetricsRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;

/**
 * 학부모 자녀 분석 조회. 셀 = (teacher_id, area_tag, type_tag) 를 <b>같은 셀</b>의 전월과 비교한다.
 *
 * <p>🔴 <b>「지난달 1위 약점」 과 「이번 달 1위 약점」 을 비교하지 않는다.</b> 이 서비스는
 * 셀을 고정하고 달만 움직인다. 순위는 이번 달 안에서만 정렬한다.</p>
 *
 * <p>🔴 학부모 self scope 를 연다 — {@link MemberDatabaseContext#withVerifiedChildScope} 밖에서
 * 부르면 정책이 통과하지 못해 <b>0행</b>이 나온다(§6-4-3 · §6-4-4).</p>
 */
@Service
public class ParentAnalysisService {

	private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
	private static final int RATIO_SCALE = 10;

	private final MemberMonthlyMetricsRepository metrics;
	private final MemberDatabaseContext databaseContext;
	private final MemberMetricsProperties properties;

	public ParentAnalysisService(
		MemberMonthlyMetricsRepository metrics,
		MemberDatabaseContext databaseContext,
		MemberMetricsProperties properties
	) {
		this.metrics = metrics;
		this.databaseContext = databaseContext;
		this.properties = properties;
	}

	@Transactional(readOnly = true)
	public AnalysisResponse getAnalysis(MemberSubject subject, UUID studentId, String month) {
		return getAnalysis(subject, studentId, month, null);
	}

	/**
	 * @param teacherFilter 🔴 <b>권한이 아니라 필터</b>다(계약 {@code TeacherIdFilter} ·
	 *                      member-api.yaml:1538-1540). {@code null} 이면 「활성 강사 전체를
	 *                      합산」이고, 값이 있으면 그 강사 셀만 합산한다.
	 *                      🔴 <b>합산 방식은 그대로다 — 먹이는 행을 좁힐 뿐이다.</b>
	 *                      산식을 갈래로 나누면 홈과 분석 화면의 같은 달 값이 갈린다.
	 *                      관계 교집합 재검증은 호출자가 이미 했다는 전제다.
	 */
	@Transactional(readOnly = true)
	public AnalysisResponse getAnalysis(
		MemberSubject subject, UUID studentId, String month, UUID teacherFilter
	) {
		validate(month);
		UUID parentId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentId);
		return databaseContext.withVerifiedChildScope(parentId, studentId,
			() -> buildAnalysis(studentId, month, teacherFilter));
	}

	private AnalysisResponse buildAnalysis(UUID studentId, String month, UUID teacherFilter) {
		// 🔴 학생 총계는 teacher_id 가 다른 여러 행의 합이다. 필터가 있으면 그 강사 행 하나만
		//    남기고, 없으면 전부 남긴다 — 아래 buildOverall 의 합산 코드는 한 벌 그대로다.
		List<MonthlyStudentMetric> studentRows = teacherFilter == null
			? metrics.findStudentByMonth(studentId, month)
			: metrics.findStudentByMonthAndTeacher(studentId, month, teacherFilter)
				.map(List::of).orElseGet(List::of);
		// 🔴 약점 셀에는 (student, month, teacher) 전용 조회가 없다. 한 학생·한 달의 셀 수는
		//    한 자릿수라 읽고 나서 좁힌다 — 조회를 새로 만들면 같은 뜻의 SQL 이 두 벌이 된다.
		List<MonthlyWeaknessMetric> weaknessRows =
			metrics.findWeaknessByMonth(studentId, month).stream()
				.filter(row -> teacherFilter == null || teacherFilter.equals(row.teacherId()))
				.toList();
		YearMonth thisMonth = YearMonth.parse(month);
		YearMonth prevMonth = thisMonth.minusMonths(1);
		String prevKey = prevMonth.toString();
		String calcVersion = studentRows.isEmpty()
			? properties.calculationVersion() : studentRows.get(0).calculationVersion();
		Instant calcAt = studentRows.isEmpty()
			? null : studentRows.get(0).calculatedAt();
		Overall overall = buildOverall(studentRows);
		List<WeaknessCellPayload> ranking = buildRanking(studentId, weaknessRows, prevKey);
		WeaknessCellPayload primary = ranking.isEmpty() ? null : ranking.get(0);
		return new AnalysisResponse(month, calcVersion, calcAt, overall, ranking, primary);
	}

	private Overall buildOverall(List<MonthlyStudentMetric> studentRows) {
		if (studentRows.isEmpty()) {
			return new Overall("NO_DATA", null, null, null);
		}
		int scored = 0;
		int correct = 0;
		long totalSec = 0;
		for (MonthlyStudentMetric row : studentRows) {
			scored += row.scoredCount();
			correct += row.correctCount();
			totalSec += row.totalActiveSec();
		}
		if (scored == 0) {
			return new Overall("NO_DATA", null, null, null);
		}
		// 🔴 표본이 임계값 미만이면 INSUFFICIENT. improvement 와 같은 임계값을 쓴다(§10-2).
		//    accuracyRate 를 0 이나 실측으로 채우면 「신뢰 가능한 값」이라는 거짓말이 된다.
		if (scored < properties.minimumSampleSize()) {
			int average = (int) (totalSec / scored);
			return new Overall("INSUFFICIENT", null, scored, average);
		}
		BigDecimal accuracy = ratio(correct, scored);
		int average = (int) (totalSec / scored);
		return new Overall("AVAILABLE", accuracy, scored, average);
	}

	private List<WeaknessCellPayload> buildRanking(
		UUID studentId, List<MonthlyWeaknessMetric> rows, String prevKey
	) {
		Map<String, MonthlyWeaknessMetric> prevIndex = prevIndex(studentId, rows, prevKey);
		List<WeaknessCellPayload> ranking = new ArrayList<>();
		for (MonthlyWeaknessMetric row : rows) {
			MonthlyWeaknessMetric prev = prevIndex.get(cellKey(row));
			WeaknessImprovement improvement = WeaknessImprovement.of(
				toCell(row),
				prev == null ? null : toCell(prev),
				properties.minimumSampleSize());
			ranking.add(new WeaknessCellPayload(
				row.teacherId(), row.areaTag(), row.typeTag(), row.status(),
				row.scoredCount(), row.correctCount(),
				row.scoredCount() == 0 ? null : ratio(row.correctCount(), row.scoredCount()),
				new Improvement(
					improvement.status().name(),
					improvement.previousAccuracyRate(),
					improvement.accuracyDeltaPp(),
					improvement.minimumSampleSize())));
		}
		// 정확도 낮은 셀부터. 동률이면 표본이 큰 셀이 위로 (신뢰 높은 약점 우선).
		ranking.sort(Comparator
			.comparing((WeaknessCellPayload cell) -> cell.accuracyRate() == null
				? BigDecimal.valueOf(2) : cell.accuracyRate())
			.thenComparing(cell -> -cell.scoredCount()));
		return ranking;
	}

	private Map<String, MonthlyWeaknessMetric> prevIndex(
		UUID studentId, List<MonthlyWeaknessMetric> currentRows, String prevKey
	) {
		Map<String, MonthlyWeaknessMetric> prevIndex = new HashMap<>();
		for (MonthlyWeaknessMetric row : currentRows) {
			for (MonthlyWeaknessMetric prev : metrics.findWeaknessByCellForTeacher(
				studentId, prevKey, row.teacherId(), row.areaTag(), row.typeTag())) {
				prevIndex.put(cellKey(prev), prev);
			}
		}
		return prevIndex;
	}

	private static String cellKey(MonthlyWeaknessMetric row) {
		return row.teacherId() + "|" + row.areaTag() + "|" + row.typeTag();
	}

	private static Cell toCell(MonthlyWeaknessMetric row) {
		return new Cell(row.scoredCount(), row.correctCount());
	}

	private static BigDecimal ratio(int correct, int scored) {
		return new BigDecimal(correct)
			.divide(new BigDecimal(scored), RATIO_SCALE, RoundingMode.HALF_UP);
	}

	private static void validate(String month) {
		if (month == null || !MONTH_PATTERN.matcher(month).matches()) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"month must be YYYY-MM");
		}
	}
}
