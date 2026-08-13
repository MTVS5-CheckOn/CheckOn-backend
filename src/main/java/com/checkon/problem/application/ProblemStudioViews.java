package com.checkon.problem.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.checkon.problem.domain.ProblemStudioEvaluation;
import com.checkon.problem.domain.ProblemTypeTag;
import com.checkon.problem.domain.ProblemValidationStatus;

public final class ProblemStudioViews {
	private ProblemStudioViews() { }

	public record StudentPage(
		List<StudentSummary> content,
		int page,
		int size,
		long totalElements
	) { }

	public record StudentSummary(
		UUID studentId,
		String studentName,
		String className,
		String subject,
		long managedDays,
		int recentSignalCount,
		String notableSummary
	) { }

	public record WeaknessAnalysis(
		UUID studentId,
		LocalDate windowStart,
		LocalDate windowEnd,
		int minimumSampleSize,
		BigDecimal studentAveragePercent,
		List<WeaknessCell> cells,
		List<GenerationCapability> generationCapabilities
	) { }

	public record GenerationCapability(
		String areaTag,
		ProblemTypeTag typeTag,
		int maximumCount,
		int recommendedMaximumCount
	) { }

	public record WeaknessCell(
		String areaTag,
		String typeTag,
		int solvedCount,
		int correctCount,
		BigDecimal accuracyPercent,
		BigDecimal gapFromStudentAveragePercent,
		ProblemStudioEvaluation evaluation
	) { }

	public record Review(
		UUID requestId,
		String requestStatus,
		String projectionStatus,
		String projectionErrorCode,
		ReviewCounts counts,
		List<ReviewItem> items
	) { }

	public record ReviewCounts(int passed, int reviewRequired, int unverifiable, int excluded) { }

	public record ReviewItem(
		UUID itemId,
		int ordinal,
		String externalItemId,
		String stem,
		String passage,
		List<Option> options,
		String correctAnswerText,
		String explanation,
		String sourceBasis,
		ProblemValidationStatus validationStatus,
		String validationMessage,
		boolean selected
	) { }

	public record Option(int position, String content, boolean correct) { }

	public record SavedSet(UUID problemSetId, int itemCount, String status, Instant savedAt) { }

	public record Assignment(UUID assignmentId, UUID problemSetId, UUID studentId, int itemCount,
		String status, Instant publishedAt) { }

	public record Printable(
		UUID requestId,
		UUID studentId,
		String studentName,
		String className,
		String subject,
		Instant generatedAt,
		List<ReviewItem> items
	) { }
}
