package com.checkon.member.analytics.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.analytics.application.dto.AnalysisResponse.Improvement;
import com.checkon.member.analytics.application.dto.WeaknessDetailResponse;
import com.checkon.member.analytics.application.dto.WeaknessDetailResponse.RecentItem;
import com.checkon.member.analytics.application.dto.WeaknessDetailResponse.Truncated;
import com.checkon.member.analytics.domain.MonthlyWeaknessMetric;
import com.checkon.member.analytics.domain.TagNormalizer;
import com.checkon.member.analytics.domain.WeaknessImprovement;
import com.checkon.member.analytics.domain.WeaknessImprovement.Cell;
import com.checkon.member.analytics.infrastructure.persistence.MemberMonthlyMetricsRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;

/**
 * 셀 하나(area × type)의 상세 — 개선도 + 최근 근거 문항.
 *
 * <p>🔴 <b>대문자 태그는 {@code 400 INVALID_REQUEST}</b>다(계약 §3-5). 소문자가 정본.
 * 5·4종 외 값도 400.</p>
 *
 * <p>🔴 최근 문항은 {@code problem_assignment_responses} 를 학생 self scope 로 읽어야 하는데,
 * 학부모 컨텍스트에서는 정책이 없어 <b>보이지 않는다</b> — 이 PR 은 <b>{@code recentItems: []}</b> 로
 * 남긴다(TODO(MB-07)). 문항 정책 추가는 별도 승인 안건이다 — 정책 없는 접근을 우회로 뚫지 않는다.</p>
 */
@Service
public class ParentWeaknessDetailService {

	private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
	private static final int RATIO_SCALE = 10;

	private final MemberMonthlyMetricsRepository metrics;
	private final MemberDatabaseContext databaseContext;
	private final MemberMetricsProperties properties;

	public ParentWeaknessDetailService(
		MemberMonthlyMetricsRepository metrics,
		MemberDatabaseContext databaseContext,
		MemberMetricsProperties properties
	) {
		this.metrics = metrics;
		this.databaseContext = databaseContext;
		this.properties = properties;
	}

	@Transactional(readOnly = true)
	public WeaknessDetailResponse getDetail(
		MemberSubject subject, UUID studentId,
		String areaTag, String typeTag, String month
	) {
		validate(areaTag, typeTag, month);
		UUID parentId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentId);
		return databaseContext.withVerifiedChildScope(parentId, studentId,
			() -> buildDetail(studentId, areaTag, typeTag, month));
	}

	private WeaknessDetailResponse buildDetail(
		UUID studentId, String areaTag, String typeTag, String month
	) {
		List<MonthlyWeaknessMetric> current = metrics.findWeaknessCell(
			studentId, month, areaTag, typeTag);
		YearMonth prevMonth = YearMonth.parse(month).minusMonths(1);
		List<MonthlyWeaknessMetric> prev = metrics.findWeaknessCell(
			studentId, prevMonth.toString(), areaTag, typeTag);
		int scored = current.stream().mapToInt(MonthlyWeaknessMetric::scoredCount).sum();
		int correct = current.stream().mapToInt(MonthlyWeaknessMetric::correctCount).sum();
		int prevScored = prev.stream().mapToInt(MonthlyWeaknessMetric::scoredCount).sum();
		int prevCorrect = prev.stream().mapToInt(MonthlyWeaknessMetric::correctCount).sum();
		WeaknessImprovement improvement = WeaknessImprovement.of(
			scored == 0 ? null : new Cell(scored, correct),
			prevScored == 0 ? null : new Cell(prevScored, prevCorrect),
			properties.minimumSampleSize());
		BigDecimal accuracy = scored == 0 ? null : ratio(correct, scored);
		String status = scored == 0
			? MonthlyWeaknessMetric.NO_DATA : MonthlyWeaknessMetric.AVAILABLE;
		List<RecentItem> recent = new ArrayList<>();
		Truncated truncated = new Truncated(false, null, null);
		return new WeaknessDetailResponse(
			areaTag, typeTag, status, scored, correct, accuracy,
			new Improvement(
				improvement.status().name(),
				improvement.previousAccuracyRate(),
				improvement.accuracyDeltaPp(),
				improvement.minimumSampleSize()),
			recent, truncated);
	}

	private static BigDecimal ratio(int correct, int scored) {
		return new BigDecimal(correct)
			.divide(new BigDecimal(scored), RATIO_SCALE, RoundingMode.HALF_UP);
	}

	private static void validate(String areaTag, String typeTag, String month) {
		if (month == null || !MONTH_PATTERN.matcher(month).matches()) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"month must be YYYY-MM");
		}
		if (!TagNormalizer.AREA_TAGS.contains(areaTag)) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"areaTag must be one of " + TagNormalizer.AREA_TAGS);
		}
		if (!TagNormalizer.TYPE_TAGS.contains(typeTag)) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"typeTag must be one of " + TagNormalizer.TYPE_TAGS);
		}
	}
}
