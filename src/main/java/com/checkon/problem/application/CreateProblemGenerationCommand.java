package com.checkon.problem.application;

import java.util.List;
import java.util.UUID;

import com.checkon.problem.domain.ProblemDifficulty;
import com.checkon.problem.domain.ProblemTargetKind;
import com.checkon.problem.domain.ProblemTypeTag;

public record CreateProblemGenerationCommand(
	ProblemTargetKind targetKind,
	UUID targetId,
	List<String> manualTargets,
	String taxonomyVersion,
	List<ProblemTypeTag> typeTags,
	int count,
	ProblemDifficulty requestedDifficulty,
	String clientIdempotencyKey
) { }
