package com.checkon.problem.application;

import java.util.List;
import java.util.UUID;

import com.checkon.problem.domain.ProblemDifficulty;
import com.checkon.problem.domain.ProblemTypeTag;

public record CreateProblemStudioCommand(
	UUID studentId,
	List<Target> targets,
	ProblemDifficulty difficulty,
	String clientIdempotencyKey
) {
	public record Target(String areaTag, ProblemTypeTag typeTag, int count) { }
}
