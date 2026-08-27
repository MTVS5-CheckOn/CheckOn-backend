package com.checkon.publication.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.publication.application.ConsultationAnswerService;
import com.checkon.publication.application.dto.ConsultationAnswerResult;
import com.checkon.publication.application.dto.TeacherConsultationDetail;
import com.checkon.publication.application.dto.TeacherConsultationSummary;
import com.checkon.publication.domain.PublicationException;

/**
 * 강사의 상담 조회와 답변 발행.
 *
 * <p>🔴 <b>경로가 {@code /api/v1/member-consultations} 인 이유</b> —
 * {@code /api/v1/member/**} 밑에 두면 {@code MemberSecurityConfiguration}({@code @Order(0)})
 * 이 가로채고 그 체인은 학생·학부모 역할로 판정한다. 강사는 401/403 을 받는다.
 * 여기는 그 matcher 밖이라 {@code AccountSecurityConfiguration}({@code @Order(2)}) 의
 * {@code /api/v1/** → hasRole("TEACHER")} 가 잡는다 — 승우님 강사 API
 * ({@code /api/v1/report-studio} 등) 와 같은 관례다.
 * 🔴 그래서 {@code AccountSecurityConfiguration} 을 <b>열 필요가 없었다.</b></p>
 *
 * <p>🔴 <b>{@code /draft} 를 만들지 않았다.</b> AI 초안 <b>본문</b>을 담는 컬럼이 스키마에
 * 없다({@code ai_status}·{@code ai_job_id} 뿐이고 계약이 「AI raw 초안은 저장하지 않는다」로
 * 정했다). 없는 것을 돌려주는 엔드포인트를 만들면 프론트가 있는 줄 안다.</p>
 *
 * <p>🔴 강사 식별은 {@code MonthlyReportController} 와 같은 방식이다 —
 * {@code @AuthenticationPrincipal AuthenticatedAccount} 에서 {@code teacherProfileId()}.</p>
 */
@RestController
@RequestMapping("/api/v1/member-consultations")
public class TeacherConsultationController {

	private final ConsultationAnswerService service;

	public TeacherConsultationController(ConsultationAnswerService service) {
		this.service = service;
	}

	@GetMapping
	public List<TeacherConsultationSummary> list(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@RequestParam(value = "status", required = false) String status,
		@RequestParam(value = "cursor", required = false) String cursor,
		@RequestParam(value = "limit", required = false) Integer limit
	) {
		return service.list(teacher(principal), status, cursor, limit);
	}

	@GetMapping("/{consultationId}")
	public TeacherConsultationDetail get(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID consultationId
	) {
		return service.get(teacher(principal), consultationId);
	}

	/**
	 * 🔴 답변 <b>발행</b>이다. 성공하면 그 순간 학부모에게 보인다 —
	 * {@code published_at} 이 NOT NULL 이라 「저장했지만 미발행」인 상태가 없다.
	 *
	 * <p>🔴 이미 답변·종료·취소된 상담은 <b>409</b> 다. 조용히 허용하지 않는다.</p>
	 */
	@PostMapping("/{consultationId}/answer")
	@ResponseStatus(HttpStatus.CREATED)
	public ConsultationAnswerResult answer(
		@AuthenticationPrincipal AuthenticatedAccount principal,
		@PathVariable UUID consultationId,
		@RequestBody AnswerRequest request
	) {
		return service.publishAnswer(teacher(principal), consultationId,
			request == null ? null : request.content());
	}

	/**
	 * 🔴 {@code teacherProfileId} 가 없으면 <b>401</b> 이다. 보안 체인이 이미 TEACHER 를
	 * 요구하지만, 프로필이 없는 계정이 통과하는 경우를 여기서 한 번 더 막는다 —
	 * {@code null} 로 조회하면 예외가 아니라 조용히 0행이 된다.
	 */
	private static UUID teacher(AuthenticatedAccount principal) {
		UUID teacherId = principal == null ? null : principal.teacherProfileId();
		if (teacherId == null) {
			throw new PublicationException(
				com.checkon.publication.domain.PublicationErrorCode.RESOURCE_NOT_FOUND,
				"teacher profile is required");
		}
		return teacherId;
	}

	/** @param content 1~2000자. V44 CHECK 와 같은 상한이다 */
	public record AnswerRequest(String content) {
	}
}
