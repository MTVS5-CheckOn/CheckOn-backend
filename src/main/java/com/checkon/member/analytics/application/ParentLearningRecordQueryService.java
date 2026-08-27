package com.checkon.member.analytics.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.analytics.application.StudentLearningRecordQueryService.Cursor;
import com.checkon.member.analytics.application.dto.LearningRecordListPage;
import com.checkon.member.analytics.application.dto.LearningRecordResponse;
import com.checkon.member.analytics.application.dto.LearningRecordResponse.TrendPoint;
import com.checkon.member.analytics.domain.LearningRecordTrend;
import com.checkon.member.analytics.domain.MonthWindow;
import com.checkon.member.analytics.infrastructure.persistence.MemberMonthlyMetricsRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.learning.domain.MemberLearningSession;
import com.checkon.member.learning.infrastructure.persistence.MemberLearningSessionRepository;

/**
 * 학부모가 자녀의 학습기록을 본다. 관계 재검증은
 * {@link MemberDatabaseContext#withVerifiedChildScope} 가 매 요청마다 한다.
 *
 * <p>🔴 관계 없음도 <b>{@code 404 RESOURCE_NOT_FOUND}</b>(계약 §3-6). 403 이 아니다.</p>
 */
@Service
public class ParentLearningRecordQueryService {

	private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
	private static final int MAX_LIMIT = 50;
	private static final int RATIO_SCALE = 10;
	private static final int TREND_MONTHS = 6;

	private final MemberLearningSessionRepository sessions;
	private final MemberDatabaseContext databaseContext;
	private final MemberMetricsProperties properties;
	private final MemberMonthlyMetricsRepository metrics;

	public ParentLearningRecordQueryService(
		MemberLearningSessionRepository sessions,
		MemberDatabaseContext databaseContext,
		MemberMetricsProperties properties,
		MemberMonthlyMetricsRepository metrics
	) {
		this.sessions = sessions;
		this.databaseContext = databaseContext;
		this.properties = properties;
		this.metrics = metrics;
	}

	@Transactional(readOnly = true)
	public LearningRecordListPage list(
		MemberSubject subject, UUID studentId, String month, String cursor, int limit
	) {
		UUID parentId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentId);
		int safeLimit = clamp(limit);
		Cursor decoded = StudentLearningRecordQueryService.decodeCursor(cursor);
		MonthWindow window = monthWindow(month);
		return databaseContext.withVerifiedChildScope(parentId, studentId,
			() -> fetchList(studentId, window, decoded, safeLimit));
	}

	private LearningRecordListPage fetchList(
		UUID studentId, MonthWindow window, Cursor cursor, int limit
	) {
		Instant from = window == null ? null : window.from();
		Instant to = window == null ? null : window.to();
		Instant cursorAt = cursor == null ? null : cursor.occurredAt();
		UUID cursorId = cursor == null ? null : cursor.id();
		List<MemberLearningSession> rows = sessions.findPage(
			studentId, from, to, cursorAt, cursorId, limit);
		String next = rows.size() < limit
			? null
			: StudentLearningRecordQueryService.encodeCursor(rows.get(rows.size() - 1));
		return new LearningRecordListPage(
			rows.stream().map(this::toSummary).toList(), next, next != null);
	}

	@Transactional(readOnly = true)
	public LearningRecordResponse getDetail(
		MemberSubject subject, UUID studentId, UUID recordId
	) {
		UUID parentId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentId);
		return databaseContext.withVerifiedChildScope(parentId, studentId, () -> {
			Optional<MemberLearningSession> found = sessions.findById(recordId, studentId);
			MemberLearningSession session = found.orElseThrow(() ->
				new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
					"learning record not found"));
			return toDetail(session, studentId);
		});
	}

	private LearningRecordResponse toDetail(MemberLearningSession session, UUID studentId) {
		ZoneId zone = ZoneId.of(properties.monthZone());
		YearMonth anchor = MonthWindow.resolveMonth(session.occurredAt(), zone);
		LearningRecordTrend.MonthRange range = LearningRecordTrend.window(anchor, TREND_MONTHS);
		List<TrendPoint> trend = LearningRecordTrend.compute(
			metrics.findStudentByMonthRange(studentId, range.fromMonth(), range.toMonth()),
			properties.minimumSampleSize()
		).stream().map(p -> new TrendPoint(p.month(), p.accuracyRate(), p.status())).toList();
		return toResponse(session, trend);
	}

	private LearningRecordResponse toSummary(MemberLearningSession session) {
		return toResponse(session, List.of());
	}

	private LearningRecordResponse toResponse(
		MemberLearningSession session, List<TrendPoint> trend
	) {
		BigDecimal accuracy = session.itemCount() == 0
			? BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP)
			: new BigDecimal(session.correctCount()).divide(
				new BigDecimal(session.itemCount()), RATIO_SCALE, RoundingMode.HALF_UP);
		ZoneId zone = ZoneId.of(properties.monthZone());
		String month = MonthWindow.resolveMonth(session.occurredAt(), zone).toString();
		return new LearningRecordResponse(
			session.id(),
			session.assignmentId(),
			session.attemptId(),
			session.titleText(),
			session.occurredAt(),
			month,
			session.itemCount(),
			session.correctCount(),
			accuracy,
			session.activeElapsedSec(),
			List.of(),
			"NO_DATA",
			trend);
	}

	private int clamp(int limit) {
		if (limit <= 0) {
			return 20;
		}
		if (limit > MAX_LIMIT) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"limit must be <= " + MAX_LIMIT);
		}
		return limit;
	}

	private MonthWindow monthWindow(String month) {
		if (month == null) {
			return null;
		}
		if (!MONTH_PATTERN.matcher(month).matches()) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"month must be YYYY-MM");
		}
		return MonthWindow.of(YearMonth.parse(month), ZoneId.of(properties.monthZone()));
	}
}
