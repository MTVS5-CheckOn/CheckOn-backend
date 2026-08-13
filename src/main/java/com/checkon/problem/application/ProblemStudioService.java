package com.checkon.problem.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.application.ProblemStudioViews.Assignment;
import com.checkon.problem.application.ProblemStudioViews.GenerationCapability;
import com.checkon.problem.application.ProblemStudioViews.Printable;
import com.checkon.problem.application.ProblemStudioViews.Review;
import com.checkon.problem.application.ProblemStudioViews.ReviewCounts;
import com.checkon.problem.application.ProblemStudioViews.ReviewItem;
import com.checkon.problem.application.ProblemStudioViews.SavedSet;
import com.checkon.problem.application.ProblemStudioViews.StudentPage;
import com.checkon.problem.application.ProblemStudioViews.StudentSummary;
import com.checkon.problem.application.ProblemStudioViews.WeaknessAnalysis;
import com.checkon.problem.application.ProblemStudioViews.WeaknessCell;
import com.checkon.problem.domain.ProblemStudioEvaluation;
import com.checkon.problem.domain.ProblemTypeTag;
import com.checkon.problem.domain.ProblemValidationStatus;
import com.checkon.problem.infrastructure.persistence.ProblemStudioQueryRepository;
import com.checkon.problem.infrastructure.persistence.ProblemStudioWorkflowRepository;

@Service
public class ProblemStudioService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final int MINIMUM_SAMPLE_SIZE = 10;
	private static final List<GenerationCapability> MVP_GENERATION_CAPABILITIES = List.of(
		new GenerationCapability("language", ProblemTypeTag.CONCEPT, 20, 3),
		new GenerationCapability("language", ProblemTypeTag.INFER, 20, 3)
	);

	private final ProblemStudioQueryRepository queries;
	private final ProblemStudioWorkflowRepository workflow;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public ProblemStudioService(
		ProblemStudioQueryRepository queries,
		ProblemStudioWorkflowRepository workflow,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.queries = queries;
		this.workflow = workflow;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public StudentPage listStudents(UUID authenticatedTeacherId, int page, int size) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		if (page < 0 || size < 1 || size > 100)
			throw ProblemGenerationException.invalidRequest("page must be at least 0 and size must be between 1 and 100");
		tenantContext.setCurrentTeacher(teacherId);
		Instant now = Instant.now(clock);
		LocalDate today = now.atZone(SEOUL).toLocalDate();
		long total = queries.countStudents(teacherId);
		List<StudentSummary> content = queries.findStudents(teacherId, now.minus(Duration.ofDays(30)), page, size)
			.stream().map(row -> {
				long days = Math.max(1, ChronoUnit.DAYS.between(row.startedAt().atZone(SEOUL).toLocalDate(), today) + 1);
				String notable = row.recentSignalCount() == 0
					? null : "최근 30일 이상 신호 %d건 발생".formatted(row.recentSignalCount());
				return new StudentSummary(row.studentId(), row.studentName(), row.className(), row.subject(),
					days, row.recentSignalCount(), notable);
			}).toList();
		int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
		return new StudentPage(content, page, size, total, totalPages);
	}

	@Transactional(readOnly = true)
	public WeaknessAnalysis analyzeWeakness(UUID authenticatedTeacherId, UUID studentId) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		Objects.requireNonNull(studentId, "studentId must not be null");
		tenantContext.setCurrentTeacher(teacherId);
		if (!queries.hasActiveStudent(teacherId, studentId)) throw ProblemGenerationException.inaccessibleTarget();
		Instant to = Instant.now(clock);
		Instant from = to.minus(Duration.ofDays(56));
		var overall = queries.findOverallAccuracy(teacherId, studentId, from, to);
		BigDecimal average = percentage(overall.correctCount(), overall.solvedCount());
		List<WeaknessCell> cells = queries.findWeaknessRows(teacherId, studentId, from, to).stream().map(row -> {
			BigDecimal accuracy = percentage(row.correctCount(), row.solvedCount());
			BigDecimal gap = average == null ? null : accuracy.subtract(average).setScale(1, RoundingMode.HALF_UP);
			ProblemStudioEvaluation evaluation;
			if (row.solvedCount() < MINIMUM_SAMPLE_SIZE || average == null) evaluation = ProblemStudioEvaluation.ON_HOLD;
			else if (accuracy.compareTo(average) >= 0) evaluation = ProblemStudioEvaluation.GOOD;
			else evaluation = ProblemStudioEvaluation.WEAK_SIGNAL;
			return new WeaknessCell(row.areaTag(), row.typeTag(), row.solvedCount(), row.correctCount(),
				accuracy, gap, evaluation);
		}).toList();
		return new WeaknessAnalysis(studentId, from.atZone(SEOUL).toLocalDate(), to.atZone(SEOUL).toLocalDate(),
			MINIMUM_SAMPLE_SIZE, average, cells, MVP_GENERATION_CAPABILITIES);
	}

	@Transactional(readOnly = true)
	public Review review(UUID authenticatedTeacherId, UUID requestId) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		tenantContext.setCurrentTeacher(teacherId);
		var request = requireRequest(teacherId, requestId);
		List<ReviewItem> items = queries.findReviewItems(teacherId, requestId, false);
		ReviewCounts counts = new ReviewCounts(
			count(items, ProblemValidationStatus.PASSED),
			count(items, ProblemValidationStatus.REVIEW_REQUIRED),
			count(items, ProblemValidationStatus.UNVERIFIABLE),
			count(items, ProblemValidationStatus.EXCLUDED)
		);
		return new Review(requestId, request.status(), request.projectionStatus(),
			request.projectionErrorCode(), counts, items);
	}

	@Transactional
	public Review updateSelection(UUID authenticatedTeacherId, UUID requestId, List<UUID> rawItemIds) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		tenantContext.setCurrentTeacher(teacherId);
		requireReviewReady(teacherId, requestId);
		if (workflow.hasSavedSet(teacherId, requestId))
			throw ProblemGenerationException.invalidState("a saved problem set cannot change its selection");
		if (rawItemIds == null || rawItemIds.size() > 20 || rawItemIds.stream().anyMatch(Objects::isNull))
			throw ProblemGenerationException.invalidRequest("itemIds must contain at most 20 non-null IDs");
		List<UUID> itemIds = List.copyOf(new LinkedHashSet<>(rawItemIds));
		if (itemIds.size() != rawItemIds.size())
			throw ProblemGenerationException.invalidRequest("itemIds must not contain duplicates");
		if (workflow.countRequestedItems(teacherId, requestId, itemIds) != itemIds.size())
			throw ProblemGenerationException.invalidRequest("only publishable items from this request can be selected");
		workflow.replaceSelection(teacherId, requestId, itemIds);
		return review(teacherId, requestId);
	}

	@Transactional
	public SavedSet save(UUID authenticatedTeacherId, UUID requestId) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		tenantContext.setCurrentTeacher(teacherId);
		requireReviewReady(teacherId, requestId);
		int selected = workflow.countPublishableSelection(teacherId, requestId);
		if (selected == 0) throw ProblemGenerationException.invalidState("at least one publishable item must be selected");
		var existing = workflow.findSavedSet(teacherId, requestId);
		var set = existing.orElseGet(() -> workflow.getOrCreateSavedSet(teacherId, requestId, Instant.now(clock)));
		if (existing.isEmpty()) workflow.snapshotSelectedItems(teacherId, requestId, set.id());
		return new SavedSet(set.id(), workflow.savedItemCount(teacherId, set.id()), set.status(), set.savedAt());
	}

	@Transactional
	public Assignment publish(UUID authenticatedTeacherId, UUID requestId) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		tenantContext.setCurrentTeacher(teacherId);
		var request = requireReviewReady(teacherId, requestId);
		if (!"STUDENT".equals(request.targetKind()) || request.studentId() == null)
			throw ProblemGenerationException.invalidState("the frontend studio publishes only student requests");
		SavedSet saved = save(teacherId, requestId);
		var assignment = workflow.publish(
			teacherId, requestId, saved.problemSetId(), request.studentId(), Instant.now(clock)
		);
		return new Assignment(assignment.id(), assignment.setId(), assignment.studentId(),
			workflow.savedItemCount(teacherId, assignment.setId()), assignment.status(), assignment.publishedAt());
	}

	@Transactional(readOnly = true)
	public Printable printable(UUID authenticatedTeacherId, UUID requestId) {
		UUID teacherId = requireTeacher(authenticatedTeacherId);
		tenantContext.setCurrentTeacher(teacherId);
		requireReviewReady(teacherId, requestId);
		var student = queries.findPrintableStudent(teacherId, requestId)
			.orElseThrow(ProblemGenerationException::notFound);
		List<ReviewItem> items = queries.findReviewItems(teacherId, requestId, true);
		if (items.isEmpty()) throw ProblemGenerationException.invalidState("at least one item must be selected for printing");
		return new Printable(requestId, student.studentId(), student.studentName(), student.className(),
			student.subject(), Instant.now(clock), items);
	}

	private ProblemStudioQueryRepository.RequestRow requireReviewReady(UUID teacherId, UUID requestId) {
		var request = requireRequest(teacherId, requestId);
		if (!("SUCCEEDED".equals(request.status()) || "PARTIAL_SUCCESS".equals(request.status())))
			throw ProblemGenerationException.invalidState("problem generation must succeed before review");
		if (!("PROJECTED".equals(request.projectionStatus()) || "PARTIAL".equals(request.projectionStatus())))
			throw ProblemGenerationException.invalidState("the AI result has no reviewable item projection");
		return request;
	}

	private ProblemStudioQueryRepository.RequestRow requireRequest(UUID teacherId, UUID requestId) {
		Objects.requireNonNull(requestId, "requestId must not be null");
		return queries.findRequest(teacherId, requestId).orElseThrow(ProblemGenerationException::notFound);
	}

	private static int count(List<ReviewItem> items, ProblemValidationStatus status) {
		return (int) items.stream().filter(item -> item.validationStatus() == status).count();
	}

	private static BigDecimal percentage(int numerator, int denominator) {
		if (denominator == 0) return null;
		return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
			.divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
	}

	private static UUID requireTeacher(UUID teacherId) {
		if (teacherId == null) throw ProblemGenerationException.invalidPrincipal();
		return teacherId;
	}
}
