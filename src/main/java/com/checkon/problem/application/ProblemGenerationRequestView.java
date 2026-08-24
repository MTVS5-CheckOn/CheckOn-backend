package com.checkon.problem.application;

import java.time.Instant;
import java.util.UUID;

import com.checkon.problem.domain.ProblemGenerationStatus;
import com.checkon.problem.domain.ProblemTargetKind;

public record ProblemGenerationRequestView(
	UUID id,
	ProblemTargetKind targetKind,
	ProblemGenerationStatus status,
	String aiJobId,
	String aiExecutionId,
	String aiSetId,
	String aiResultStatus,
	String errorCode,
	String resultPayload,
	String versionsPayload,
	Instant requestedAt,
	Instant dispatchedAt,
	Instant completedAt
) { }
