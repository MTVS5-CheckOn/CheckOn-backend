package com.checkon.member.learning.presentation;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.learning.application.AttemptInProgressResponse;
import com.checkon.member.learning.application.StudentAttemptService;
import com.checkon.member.learning.application.StudentAttemptService.AttemptRaceLostException;
import com.checkon.member.learning.application.StudentAttemptService.StartOutcome;
import com.checkon.member.learning.application.dto.AttemptProgressRequest;
import com.checkon.member.learning.application.dto.AttemptProgressResult;

/**
 * attempt 시작·재개·조회·progress 자동저장 (§4·§5·§6). 계약 operationId 와 1:1 이다:
 * <ul>
 *   <li>{@code startStudentAttempt} — {@code POST /worksheets/{assignmentId}/attempts}</li>
 *   <li>{@code getStudentAttempt} — {@code GET /attempts/{attemptId}}</li>
 *   <li>{@code saveStudentAttemptProgress} — {@code PATCH /attempts/{attemptId}/progress}</li>
 * </ul>
 *
 * <p>🔴 201 vs 200 은 서버가 구분해서 낸다(분기표 §1). body 는 동일 스키마이지만
 * 로그·지표가 「신규 시작」과 「재개」를 구별해야 한다.</p>
 *
 * <p>🔴 대기 학생은 {@code StudentActivationGuard} 가 이 경로 전체를 403 으로 막는다 —
 * 컨트롤러가 다시 판정하지 않는다(설계 §4-4).</p>
 */
@RestController
@RequestMapping("/api/v1/member/students/me")
public class StudentAttemptController {

	private final StudentAttemptService attemptService;

	public StudentAttemptController(StudentAttemptService attemptService) {
		this.attemptService = attemptService;
	}

	@PostMapping("/worksheets/{assignmentId}/attempts")
	public ResponseEntity<MemberResponse<AttemptInProgressResponse>> startStudentAttempt(
		@CurrentMember MemberSubject subject,
		@PathVariable("assignmentId") UUID assignmentId
	) {
		StartOutcome outcome;
		try {
			outcome = attemptService.startOrResume(subject, assignmentId);
		}
		catch (AttemptRaceLostException race) {
			// 🔴 재시도 상한 1회. 재시도 트랜잭션이 새로 열려 findOpen 이 성공해야 한다.
			outcome = attemptService.recoverAfterRace(subject, race.assignmentId());
		}
		HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(MemberResponse.of(outcome.response()));
	}

	@GetMapping("/attempts/{attemptId}")
	public MemberResponse<AttemptInProgressResponse> getStudentAttempt(
		@CurrentMember MemberSubject subject,
		@PathVariable("attemptId") UUID attemptId
	) {
		return MemberResponse.of(attemptService.getAttempt(subject, attemptId));
	}

	@PatchMapping("/attempts/{attemptId}/progress")
	public MemberResponse<AttemptProgressResult> saveStudentAttemptProgress(
		@CurrentMember MemberSubject subject,
		@PathVariable("attemptId") UUID attemptId,
		@RequestBody AttemptProgressRequest request
	) {
		return MemberResponse.of(attemptService.saveProgress(subject, attemptId, request));
	}
}
