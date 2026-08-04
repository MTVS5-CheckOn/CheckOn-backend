package com.checkon.learning.presentation;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.learning.application.LearningRecordSource;
import com.checkon.learning.application.RegisterLearningRecordCommand;
import com.checkon.learning.application.RegisterLearningRecordService;
import com.checkon.learning.domain.LearningRecordType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/learning-records")
public class LearningRecordController {
	private final RegisterLearningRecordService registrationService;

	public LearningRecordController(RegisterLearningRecordService registrationService) {
		this.registrationService = registrationService;
	}

	@PostMapping
	public ResponseEntity<LearningRecordResponse> register(
		@AuthenticationPrincipal AuthenticatedAccount authenticatedAccount,
		@Valid @RequestBody LearningRecordRequest request
	) {
		// 테넌트 경계는 조작 가능한 요청 값이 아니라 JWT와 현재 DB 세션을 다시
		// 확인해 만든 인증 주체의 teacherProfileId에서만 가져온다.
		UUID teacherProfileId = authenticatedAccount == null
			? null
			: authenticatedAccount.teacherProfileId();
		UUID recordId = registrationService.register(
			teacherProfileId,
			request.toCommand()
		);
		return ResponseEntity.created(
			URI.create("/api/v1/learning-records/" + recordId)
		).body(new LearningRecordResponse(recordId));
	}

	public record LearningRecordRequest(
		@NotNull UUID studentId,
		UUID classGroupId,
		@NotNull LearningRecordType recordType,
		@NotNull Instant occurredAt,
		@Size(max = 255) String externalRecordRef,
		Boolean correct,
		@PositiveOrZero Integer durationSec,
		@PositiveOrZero Integer passageWordCount,
		@Size(max = 80) String areaTag,
		@Size(max = 80) String subjectTrack,
		@Size(max = 80) String typeTag,
		@Size(max = 80) String itemFormat,
		@Size(max = 255) String assignmentTitleText
	) {
		RegisterLearningRecordCommand toCommand() {
			return new RegisterLearningRecordCommand(
				studentId, classGroupId, recordType, occurredAt, LearningRecordSource.MANUAL,
				externalRecordRef, correct, durationSec, passageWordCount, areaTag,
				subjectTrack, typeTag, itemFormat, assignmentTitleText
			);
		}
	}

	public record LearningRecordResponse(UUID id) {
	}
}
