package com.checkon.member.analytics.application;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.analytics.domain.MonthWindow;
import com.checkon.member.analytics.domain.MonthlyStudentMetric;
import com.checkon.member.analytics.domain.MonthlyWeaknessMetric;
import com.checkon.member.analytics.domain.TagNormalizer;
import com.checkon.member.analytics.infrastructure.persistence.MemberMonthlyMetricsRepository;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.integration.learning.LearningRecordAggregationAdapter;
import com.checkon.member.integration.learning.LearningRecordAggregationAdapter.AggregatedCell;

/**
 * 한 (studentId, month) 를 결정론적으로 재계산해 두 metrics 테이블에 UPSERT 한다.
 *
 * <p>🔴 <b>원천은 {@code problem_assignment_responses}</b>(V33). 지시서 §180 재측정 참고 —
 * {@code learning_records} 태그는 우회로 채워져 있어 원천으로 못 쓴다.</p>
 *
 * <p>🔴 <b>학생 컨텍스트를 스스로 연다.</b> {@link MemberDatabaseContext#setCurrentStudent} 를
 * 트랜잭션 진입에서 부른다. 밖에서 부르면 정책이 통과하지 못해 조용히 0행이 나온다(§6-4-4).
 * 🔴 <b>강사 컨텍스트를 절대 열지 않는다</b>(G1).</p>
 *
 * <p>🔴 {@code scored_count = 0} 인 셀은 <b>행을 만들지 않는다</b> — 없는 것과 0 은 다르다.
 * 목록의 문항 수 합과 이 집계 결과는 <b>일치하지 않을 수 있다</b>(§6, 조회 서비스 주석).</p>
 */
@Service
public class MonthlyMetricsAggregator {

	private static final Logger log = LoggerFactory.getLogger(MonthlyMetricsAggregator.class);

	private final LearningRecordAggregationAdapter source;
	private final MemberMonthlyMetricsRepository metrics;
	private final MemberDatabaseContext databaseContext;
	private final MemberMetricsProperties properties;
	private final Clock clock;

	public MonthlyMetricsAggregator(
		LearningRecordAggregationAdapter source,
		MemberMonthlyMetricsRepository metrics,
		MemberDatabaseContext databaseContext,
		MemberMetricsProperties properties,
		Clock clock
	) {
		this.source = source;
		this.metrics = metrics;
		this.databaseContext = databaseContext;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * (student, month) 하나를 재계산한다. 학생 self 컨텍스트에서 돌아야 한다.
	 *
	 * @param accountId 학생 계정 id (컨텍스트 세팅용)
	 * @param studentId 학생 프로필 id
	 * @param month     YYYY-MM
	 */
	@Transactional
	public void recompute(UUID accountId, UUID studentId, YearMonth month) {
		Objects.requireNonNull(month, "month must not be null");
		databaseContext.setCurrentAccount(accountId);
		databaseContext.setCurrentStudent(studentId);
		ZoneId zone = ZoneId.of(properties.monthZone());
		MonthWindow window = MonthWindow.of(month, zone);

		List<AggregatedCell> raw = source.aggregate(studentId, window.from(), window.to());
		Instant calculatedAt = clock.instant();
		String monthKey = month.toString();
		String monthZone = zone.getId();
		String version = properties.calculationVersion();

		int droppedRows = 0;
		int droppedCells = 0;
		int keptCells = 0;
		Map<UUID, int[]> perTeacherTotals = new HashMap<>();
		for (AggregatedCell cell : raw) {
			String area = TagNormalizer.normalizeArea(cell.areaTag());
			String type = TagNormalizer.normalizeType(cell.typeTag());
			if (area == null || type == null) {
				droppedRows += cell.scoredCount();
				droppedCells += 1;
				continue;
			}
			keptCells += 1;
			metrics.upsertWeakness(new MonthlyWeaknessMetric(
				cell.teacherId(), studentId, monthKey, monthZone,
				area, type, cell.scoredCount(), cell.correctCount(),
				cell.scoredCount() > 0
					? MonthlyWeaknessMetric.AVAILABLE
					: MonthlyWeaknessMetric.NO_DATA,
				version, calculatedAt));
			int[] agg = perTeacherTotals.computeIfAbsent(
				cell.teacherId(), key -> new int[2]);
			agg[0] += cell.scoredCount();
			agg[1] += cell.correctCount();
		}
		for (Map.Entry<UUID, int[]> entry : perTeacherTotals.entrySet()) {
			int[] totals = entry.getValue();
			metrics.upsertStudent(new MonthlyStudentMetric(
				entry.getKey(), studentId, monthKey, monthZone,
				totals[0], totals[1], 0, version, calculatedAt));
		}
		if (droppedRows > 0) {
			log.info("member.metrics.dropped st={} m={} cells={} rows={} why=unmapped",
				studentId, monthKey, droppedCells, droppedRows);
		}
		log.info("member.metrics.recomputed studentId={} month={} teachers={} cells={}",
			studentId, monthKey, perTeacherTotals.size(), keptCells);
	}
}
