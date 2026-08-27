package com.checkon.member.analytics.application;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.analytics.application.dto.AnalysisResponse;
import com.checkon.member.analytics.application.dto.AnalysisResponse.Improvement;
import com.checkon.member.analytics.application.dto.AnalysisResponse.Overall;
import com.checkon.member.analytics.application.dto.AnalysisResponse.WeaknessCellPayload;
import com.checkon.member.analytics.application.dto.LearningRecordResponse;
import com.checkon.member.analytics.domain.HomeMetric;
import com.checkon.member.analytics.domain.MonthWindow;
import com.checkon.member.analytics.domain.ParentHomeMetrics;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.common.security.ParentChildAccessGuard;

/**
 * 자녀 홈의 <b>analytics 몫</b>만 만든다 — 지표 4종과 최근 기록.
 *
 * <p>🔴 <b>새로 집계하지 않는다.</b> {@link ParentAnalysisService} 와
 * {@link ParentLearningRecordQueryService} 가 낸 값을 계약의 이름으로 옮길 뿐이다. 홈이
 * 자기 산식을 가지면 같은 달의 정확도가 홈과 분석 화면에서 다르게 나온다.</p>
 *
 * <p>🔴 {@code child} 와 {@code latestReport} 는 <b>여기서 만들지 않는다.</b> 그 둘은 다른
 * sub-context({@code membership}·{@code report}) 소유이고, 조립은 presentation 한 곳에서 한다
 * (설계 §3-1 · CLAUDE.md §1-6 「sub-context 끼리 직접 참조 금지」).</p>
 */
@Service
public class ParentHomeService {

	private final ParentAnalysisService analysisService;
	private final ParentLearningRecordQueryService recordQueryService;
	private final ParentChildAccessGuard accessGuard;
	private final MemberMetricsProperties properties;
	private final Clock clock;

	public ParentHomeService(
		ParentAnalysisService analysisService,
		ParentLearningRecordQueryService recordQueryService,
		ParentChildAccessGuard accessGuard,
		MemberMetricsProperties properties,
		Clock clock
	) {
		this.analysisService = analysisService;
		this.recordQueryService = recordQueryService;
		this.accessGuard = accessGuard;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 자녀 관계와 {@code teacherId} 필터를 <b>매 요청 재검증</b>한다.
	 *
	 * <p>🔴 관계가 없거나(연결 안 됨) 끝났으면(ENDED) {@code withVerifiedChild} 가
	 * {@code MemberScopeDeniedException} 을 던지고 핸들러가 <b>404</b> 로 옮긴다 —
	 * 과거 기록 열람은 MB-08 미확정이라 지금은 fail-closed 다.</p>
	 *
	 * <p>🔴 {@code teacherId} 는 <b>권한이 아니라 필터</b>지만, 관계 교집합 밖을 가리키면
	 * <b>404</b> 다(분기표 §4 홈 표). 「그 강사가 존재하는가」를 알려주지 않는다.</p>
	 */
	@Transactional(readOnly = true)
	public void requireChildAccess(MemberSubject subject, UUID studentId, UUID teacherId) {
		accessGuard.withVerifiedChild(subject, studentId, access -> {
			if (!accessGuard.allowsTeacherFilter(access, teacherId)) {
				throw new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
					"child home not found");
			}
			return null;
		});
	}

	/**
	 * 지표 4종. 달은 <b>서버가</b> 주입된 {@code Clock} 과 설정 zone 으로 정한다 —
	 * 홈에는 month 파라미터가 없다(계약 {@code getParentChildHome}).
	 */
	@Transactional(readOnly = true)
	public List<HomeMetric> metrics(MemberSubject subject, UUID studentId) {
		AnalysisResponse analysis =
			analysisService.getAnalysis(subject, studentId, currentMonth());
		Overall overall = analysis.overall();
		WeaknessCellPayload primary = analysis.primaryWeakness();
		Improvement improvement = primary == null ? null : primary.improvement();
		return ParentHomeMetrics.of(
			overall.status(), overall.accuracyRate(), overall.scoredCount(),
			overall.averageActiveSeconds(),
			improvement == null ? null : improvement.status(),
			improvement == null ? null : improvement.accuracyDeltaPp());
	}

	/**
	 * 최근 기록. 🔴 개수는 설정({@code home-recent-record-limit})이다 — 상수를 코드에 박지 않는다.
	 * 기록이 없으면 <b>빈 배열</b>이고 오류가 아니다.
	 */
	public List<LearningRecordResponse> recentRecords(MemberSubject subject, UUID studentId) {
		return recordQueryService
			.list(subject, studentId, null, null, properties.homeRecentRecordLimit())
			.items();
	}

	private String currentMonth() {
		ZoneId zone = ZoneId.of(properties.monthZone());
		YearMonth month = MonthWindow.resolveMonth(clock.instant(), zone);
		return month.toString();
	}
}
