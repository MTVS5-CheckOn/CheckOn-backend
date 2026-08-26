package com.checkon.problem.application;

import java.util.List;
import java.util.UUID;

import com.checkon.problem.domain.ProblemDifficulty;
import com.checkon.problem.domain.ProblemTypeTag;

public record CreateProblemStudioCommand(
	UUID studentId,
	UUID diagnosisId,
	List<Target> targets,
	ProblemDifficulty difficulty,
	String clientIdempotencyKey
) {
	public record Target(
		String areaTag,
		ProblemTypeTag typeTag,
		int count,
		String skillNodeId,
		Passage passage,
		WorkSelection workSelection
	) { }

	public record Passage(
		String areaTag,
		String domain,
		String topicHint,
		Integer wordCount,
		String sentenceComplexity,
		Integer paragraphCount,
		String sourceKind,
		String bannedTopicsVersion
	) { }

	public record WorkSelection(String genre, String era, List<String> conceptKeywords) { }
}
