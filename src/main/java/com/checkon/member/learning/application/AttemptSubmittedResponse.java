package com.checkon.member.learning.application;

import java.time.Instant;
import java.util.UUID;

/** 채점 전 SUBMITTED 상태의 폴링 응답. 정답·해설 필드는 구조적으로 존재하지 않는다. */
public record AttemptSubmittedResponse(
	UUID attemptId,
	UUID assignmentId,
	String status,
	int version,
	Instant submittedAt
) {
}
