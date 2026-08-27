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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 학생 학습기록 목록/상세.
 *
 * <p>🔴 <b>여기 나오는 것은 attempt 기반 세션만</b>이다 — 강사가 엑셀로 넣은
 * {@code learning_records} 원본은 세션이 없어 여기 없다. 그러나 <b>월 집계에는 그 원본이 들어간다</b>
 * (§6). 목록의 문항 수 합과 분석의 {@code scoredCount} 가 <b>일치하지 않을 수 있다</b> —
 * 그것을 계약이 명시적으로 허용한다. 응답에서 두 값이 같다고 주장하는 필드를 만들지 마라.</p>
 */
@Service
public class StudentLearningRecordQueryService {

	private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
	private static final int MAX_LIMIT = 50;
	private static final int RATIO_SCALE = 10;

	private final MemberLearningSessionRepository sessions;
	private final MetricRefreshOutboxDrainer drainer;
	private final MemberDatabaseContext databaseContext;
	private final MemberMetricsProperties properties;
	private final MemberMonthlyMetricsRepository metrics;
	private final TransactionTemplate readOnlyTransactionTemplate;

	public StudentLearningRecordQueryService(
		MemberLearningSessionRepository sessions,
		MetricRefreshOutboxDrainer drainer,
		MemberDatabaseContext databaseContext,
		MemberMetricsProperties properties,
		MemberMonthlyMetricsRepository metrics,
		PlatformTransactionManager transactionManager
	) {
		this.sessions = sessions;
		this.drainer = drainer;
		this.databaseContext = databaseContext;
		this.properties = properties;
		this.metrics = metrics;
		this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
		// 🔴 drain 이후의 read 트랜잭션이라 REQUIRES_NEW. read-only 힌트로 커넥션에 반영한다.
		this.readOnlyTransactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readOnlyTransactionTemplate.setReadOnly(true);
	}

	/**
	 * 목록. 🔴 진입에서 <b>자기 outbox 를 인라인 drain</b>한다(설계 §11-1). drain 은
	 * {@link MetricRefreshOutboxDrainer} 자체 트랜잭션이라, 여기 read 트랜잭션과 분리된다.
	 *
	 * <p>🔴 <b>{@code @Transactional} 로 read 트랜잭션을 열지 않는다</b> — 그러면 drain 이
	 * 이 read 트랜잭션에 합쳐져 write 를 read-only 커넥션이 삼킨다. 대신
	 * {@link TransactionTemplate} 로 drain 이후에 별도 read 트랜잭션을 연다. 자기 호출로 어노테이션이
	 * 무시되는 문제도 여기서 함께 없앤다(자기 호출은 프록시를 통과하지 않는다).</p>
	 */
	public LearningRecordListPage list(
		MemberSubject subject, String month, String cursor, int limit
	) {
		UUID studentId = subject.requireStudentProfileId();
		drainer.drainForStudent(subject.accountId(), studentId);
		int safeLimit = clamp(limit);
		Cursor decoded = decodeCursor(cursor);
		MonthWindow window = monthWindow(month);
		return readOnlyTransactionTemplate.execute(status ->
			readList(subject.accountId(), studentId, window, decoded, safeLimit));
	}

	private LearningRecordListPage readList(
		UUID accountId, UUID studentId, MonthWindow window, Cursor cursor, int limit
	) {
		databaseContext.setCurrentAccount(accountId);
		databaseContext.setCurrentStudent(studentId);
		Instant from = window == null ? null : window.from();
		Instant to = window == null ? null : window.to();
		Instant cursorAt = cursor == null ? null : cursor.occurredAt();
		UUID cursorId = cursor == null ? null : cursor.id();
		List<MemberLearningSession> rows = sessions.findPage(
			studentId, from, to, cursorAt, cursorId, limit);
		String next = rows.size() < limit
			? null : encodeCursor(rows.get(rows.size() - 1));
		return new LearningRecordListPage(
			rows.stream().map(this::toSummary).toList(), next, next != null);
	}

	@Transactional(readOnly = true)
	public LearningRecordResponse getDetail(MemberSubject subject, UUID recordId) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);
		Optional<MemberLearningSession> found = sessions.findById(recordId, studentId);
		MemberLearningSession session = found.orElseThrow(() -> new MemberException(
			MemberErrorCode.RESOURCE_NOT_FOUND, "learning record not found"));
		return toDetail(session, studentId);
	}

	private LearningRecordResponse toDetail(MemberLearningSession session, UUID studentId) {
		ZoneId zone = ZoneId.of(properties.monthZone());
		YearMonth anchor = MonthWindow.resolveMonth(session.occurredAt(), zone);
		LearningRecordTrend.MonthRange range = LearningRecordTrend.window(anchor, properties.trendMonths());
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
			// 🔴 weakness 는 여기서 태그별로 계산하려면 문항별 태그가 필요하다 — MB-07 확정 후.
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

	static Cursor decodeCursor(String encoded) {
		if (encoded == null || encoded.isBlank()) {
			return null;
		}
		try {
			String raw = new String(java.util.Base64.getUrlDecoder().decode(encoded));
			int colon = raw.indexOf(':');
			if (colon <= 0) {
				throw new IllegalArgumentException("bad cursor");
			}
			long epochMilli = Long.parseLong(raw.substring(0, colon));
			UUID id = UUID.fromString(raw.substring(colon + 1));
			return new Cursor(Instant.ofEpochMilli(epochMilli), id);
		}
		catch (RuntimeException error) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST, "invalid cursor");
		}
	}

	static String encodeCursor(MemberLearningSession row) {
		String raw = row.occurredAt().toEpochMilli() + ":" + row.id();
		return java.util.Base64.getUrlEncoder().withoutPadding()
			.encodeToString(raw.getBytes());
	}

	/** decoded cursor. */
	public record Cursor(Instant occurredAt, UUID id) {
	}
}
