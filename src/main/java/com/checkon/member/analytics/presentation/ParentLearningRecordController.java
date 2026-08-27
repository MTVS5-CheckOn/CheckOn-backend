package com.checkon.member.analytics.presentation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.analytics.application.ParentAnalysisService;
import com.checkon.member.analytics.application.ParentLearningRecordQueryService;
import com.checkon.member.analytics.application.ParentWeaknessDetailService;
import com.checkon.member.analytics.application.dto.AnalysisResponse;
import com.checkon.member.analytics.application.dto.LearningRecordListPage;
import com.checkon.member.analytics.application.dto.LearningRecordResponse;
import com.checkon.member.analytics.application.dto.WeaknessDetailResponse;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;

/**
 * 학부모 자녀 분석·기록. 자녀 관계 재검증은 서비스가 매 요청마다
 * {@code withVerifiedChildScope} 로 한다.
 *
 * <p>🔴 관계 없음도 <b>404 RESOURCE_NOT_FOUND</b>(계약 §3-6). 403 이 아니다.</p>
 */
@RestController
@RequestMapping("/api/v1/member/parents/me/children/{studentId}")
public class ParentLearningRecordController {

	private final ParentLearningRecordQueryService recordService;
	private final ParentAnalysisService analysisService;
	private final ParentWeaknessDetailService weaknessDetailService;

	public ParentLearningRecordController(
		ParentLearningRecordQueryService recordService,
		ParentAnalysisService analysisService,
		ParentWeaknessDetailService weaknessDetailService
	) {
		this.recordService = recordService;
		this.analysisService = analysisService;
		this.weaknessDetailService = weaknessDetailService;
	}

	@GetMapping("/learning-records")
	public MemberResponse<LearningRecordListPage> listRecords(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@RequestParam(value = "month", required = false) String month,
		@RequestParam(value = "cursor", required = false) String cursor,
		@RequestParam(value = "limit", defaultValue = "20") int limit
	) {
		// 🔴 계약(member-api.yaml:927)은 이 경로에도 TeacherIdFilter 를 두는데 여기서는
		//    아직 받지 않는다 — 분기표에 그 행이 없어서 「표를 먼저 고친다」 규칙에 걸린다.
		//    MB-60 에 등재했다. 지금은 「생략」과 같은 뜻인 null 을 명시적으로 넘긴다.
		return MemberResponse.of(
			recordService.list(subject, studentId, null, month, cursor, limit));
	}

	@GetMapping("/learning-records/{recordId}")
	public MemberResponse<LearningRecordResponse> recordDetail(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@PathVariable UUID recordId
	) {
		return MemberResponse.of(recordService.getDetail(subject, studentId, recordId));
	}

	@GetMapping("/analysis")
	public MemberResponse<AnalysisResponse> analysis(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@RequestParam(value = "month") String month
	) {
		return MemberResponse.of(analysisService.getAnalysis(subject, studentId, month));
	}

	@GetMapping("/analysis/weaknesses/{areaTag}/{typeTag}")
	public MemberResponse<WeaknessDetailResponse> weaknessDetail(
		@CurrentMember MemberSubject subject,
		@PathVariable UUID studentId,
		@PathVariable String areaTag,
		@PathVariable String typeTag,
		@RequestParam(value = "month") String month
	) {
		return MemberResponse.of(weaknessDetailService.getDetail(
			subject, studentId, areaTag, typeTag, month));
	}
}
