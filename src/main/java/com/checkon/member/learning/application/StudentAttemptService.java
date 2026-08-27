package com.checkon.member.learning.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.ConstraintViolations;
import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.problem.PublishedItemOption;
import com.checkon.member.integration.problem.PublishedItemSnapshot;
import com.checkon.member.integration.problem.PublishedWorksheetAdapter;
import com.checkon.member.learning.application.dto.AttemptProgressRequest;
import com.checkon.member.learning.application.dto.AttemptProgressResult;
import com.checkon.member.learning.domain.MemberAttempt;
import com.checkon.member.learning.domain.MemberAttemptEventType;
import com.checkon.member.learning.domain.MemberAttemptItemRow;
import com.checkon.member.learning.domain.MemberAttemptStatus;
import com.checkon.member.learning.domain.MemberLearningSession;
import com.checkon.member.learning.domain.StudentAssignmentRow;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptAnswerRepository;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptEventRepository;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptItemRepository;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptRepository;
import com.checkon.member.learning.infrastructure.persistence.MemberLearningSessionRepository;
import com.checkon.member.learning.infrastructure.persistence.StudentWorksheetQueryRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * attempt 시작·재개·조회 + progress 자동저장. 서비스가 <b>자기 트랜잭션에서</b> 학생 컨텍스트를
 * 열고 이 안에서만 attempt 리포지토리를 부른다(설계 §6-4-4 · G15).
 *
 * <p>🔴 <b>새 세터를 만들지 않는다</b> — 스코프는
 * {@link MemberDatabaseContext#withVerifiedProblemSetScope} 로만 연다(NEXT.md §1 「scope」).
 * 학생↔학습지 소유 확인이 세팅과 한 메서드에 묶여 있어 <b>확인을 건너뛸 문법적 방법이 없다</b>.</p>
 *
 * <p>🔴 <b>복사 이후 조회는 {@code member_*} 만 본다.</b> attempt 시작 시 스냅샷을
 * {@code member_attempt_items} 로 동결하고, 이후 GET/progress 는 이 테이블만 읽는다
 * (PR5 §4). {@code saved_problem_set_items} 를 조회 경로에서 다시 읽으면 반려다.</p>
 */
@Service
public class StudentAttemptService {

	private final StudentWorksheetQueryRepository worksheetQuery;
	private final MemberAttemptRepository attemptRepository;
	private final MemberAttemptItemRepository itemRepository;
	private final MemberAttemptAnswerRepository answerRepository;
	private final MemberAttemptEventRepository eventRepository;
	private final MemberLearningSessionRepository sessionRepository;
	private final PublishedWorksheetAdapter publishedWorksheet;
	private final MemberDatabaseContext databaseContext;
	private final AttemptProgressValidator progressValidator;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public StudentAttemptService(
		StudentWorksheetQueryRepository worksheetQuery,
		MemberAttemptRepository attemptRepository,
		MemberAttemptItemRepository itemRepository,
		MemberAttemptAnswerRepository answerRepository,
		MemberAttemptEventRepository eventRepository,
		MemberLearningSessionRepository sessionRepository,
		PublishedWorksheetAdapter publishedWorksheet,
		MemberDatabaseContext databaseContext,
		MemberAttemptProperties properties,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.worksheetQuery = worksheetQuery;
		this.attemptRepository = attemptRepository;
		this.itemRepository = itemRepository;
		this.answerRepository = answerRepository;
		this.eventRepository = eventRepository;
		this.sessionRepository = sessionRepository;
		this.publishedWorksheet = publishedWorksheet;
		this.databaseContext = databaseContext;
		this.progressValidator = new AttemptProgressValidator(properties.maxProgressDeltaSeconds());
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * 시작 또는 재개. 새로 만들면 {@code created=true}, 재개면 {@code false} —
	 * 컨트롤러가 이 값으로 201/200 을 가른다. 응답 body 는 <b>같은 트랜잭션에서 projection</b>
	 * 까지 조립한다 — 컨트롤러로 나가면 트랜잭션이 닫혀 RLS 컨텍스트가 새 커넥션에 없다.
	 */
	@Transactional
	public StartOutcome startOrResume(MemberSubject subject, UUID assignmentId) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		StudentAssignmentRow assignment = worksheetQuery.findAssignment(assignmentId, studentId)
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.RESOURCE_NOT_FOUND, "assignment not found"));

		Optional<MemberAttempt> open = attemptRepository.findOpen(studentId, assignmentId);
		if (open.isPresent()) {
			eventRepository.insert(open.get().id(), MemberAttemptEventType.RESUMED,
				null, null, clock.instant());
			return new StartOutcome(projectInProgress(open.get()), false);
		}
		if (hasScoredAttempt(studentId, assignmentId)) {
			throw new MemberException(MemberErrorCode.ATTEMPT_ALREADY_SUBMITTED,
				"attempt already submitted");
		}

		List<PublishedItemSnapshot> snapshots = databaseContext.withVerifiedProblemSetScope(
			studentId, assignment.problemSetId(),
			() -> publishedWorksheet.snapshotsOf(assignment.problemSetId()));
		AttemptGradabilityCheck.verify(snapshots);

		MemberAttempt created = createAttempt(studentId, assignment, snapshots);
		return new StartOutcome(projectInProgress(created), true);
	}

	/**
	 * 재개용 <b>새 트랜잭션</b>. 23505 이후 aborted 트랜잭션에서는 못 조회하므로 컨트롤러가
	 * 롤백 후 이 메서드를 부른다(재시도 상한 1회). findOpen 이 0건이면 IllegalStateException —
	 * race 를 잡았는데 이후에 사라진 것은 설계 밖 상태이므로 500 이 맞다.
	 */
	@Transactional
	public StartOutcome recoverAfterRace(MemberSubject subject, UUID assignmentId) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);
		MemberAttempt existing = attemptRepository.findOpen(studentId, assignmentId)
			.orElseThrow(() -> new IllegalStateException(
				"unique-index race resolved with no open attempt visible"));
		eventRepository.insert(existing.id(), MemberAttemptEventType.RESUMED,
			null, null, clock.instant());
		return new StartOutcome(projectInProgress(existing), false);
	}

	/** {@code GET /member/students/me/attempts/{attemptId}}. */
	@Transactional(readOnly = true)
	public Object getAttempt(MemberSubject subject, UUID attemptId) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);
		MemberAttempt attempt = attemptRepository.findById(attemptId)
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.RESOURCE_NOT_FOUND, "attempt not found"));
		if (attempt.status() == MemberAttemptStatus.IN_PROGRESS) {
			return projectInProgress(attempt);
		}
		if (attempt.status() == MemberAttemptStatus.SUBMITTED) {
			return new AttemptSubmittedResponse(
				attempt.id(), attempt.assignmentId(), "SUBMITTED",
				attempt.version(), attempt.submittedAt());
		}
		return projectResult(attempt);
	}

	/** {@code GET /member/students/me/attempts/{attemptId}/result}. SCORED 전에는 404다. */
	@Transactional(readOnly = true)
	public AttemptResult getResult(MemberSubject subject, UUID attemptId) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);
		MemberAttempt attempt = attemptRepository.findById(attemptId)
			.filter(found -> found.status() == MemberAttemptStatus.SCORED)
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.RESOURCE_NOT_FOUND, "scored attempt not found"));
		return projectResult(attempt);
	}

	/** {@code PATCH /member/students/me/attempts/{attemptId}/progress}. */
	@Transactional
	public AttemptProgressResult saveProgress(
		MemberSubject subject, UUID attemptId, AttemptProgressRequest request
	) {
		progressValidator.validateHeader(request);
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		MemberAttempt attempt = attemptRepository.lockById(attemptId)
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.RESOURCE_NOT_FOUND, "attempt not found"));
		if (attempt.status() != MemberAttemptStatus.IN_PROGRESS) {
			throw new MemberException(MemberErrorCode.ATTEMPT_ALREADY_SUBMITTED,
				"attempt already submitted");
		}
		Integer last = attempt.lastClientSequence();
		if (last != null && request.clientSequence() <= last) {
			// 🔴 dedupe — 아무것도 쓰지 않고 200 duplicated=true. 시간·version 불변.
			return new AttemptProgressResult(
				attempt.id(), attempt.version(), attempt.activeElapsedSec(),
				attempt.lastProgressAt() == null ? attempt.startedAt() : attempt.lastProgressAt(),
				true);
		}
		if (request.baseVersion() != attempt.version()) {
			// 🔴 낙관락 — 병합하지 마라. 클라이언트가 재조회한다.
			throw new MemberException(MemberErrorCode.REVISION_CONFLICT,
				"base version mismatch",
				List.of(new FieldViolation("currentVersion",
					Integer.toString(attempt.version()))));
		}

		List<MemberAttemptItemRow> items = itemRepository.findByAttempt(attempt.id());
		List<UUID> validIds = items.stream().map(MemberAttemptItemRow::itemId).toList();
		Map<UUID, Integer> optionCounts = optionCountsFrom(items);
		Map<UUID, Integer> answers = request.answers() == null ? Map.of() : request.answers();
		Map<UUID, Integer> deltas = request.activeElapsedSecondsDelta() == null
			? Map.of() : request.activeElapsedSecondsDelta();
		progressValidator.validateBody(answers, deltas, validIds, optionCounts);

		Instant now = clock.instant();
		int addedSeconds = writeAnswers(attempt.id(), answers, deltas, now);
		int newVersion = attempt.version() + 1;
		int newActive = attempt.activeElapsedSec() + addedSeconds;
		attemptRepository.updateProgress(attempt.id(), newVersion, newActive,
			request.clientSequence(), now);
		eventRepository.insert(attempt.id(), MemberAttemptEventType.PROGRESS,
			request.currentItemId(), request.clientSequence(), now);
		return new AttemptProgressResult(attempt.id(), newVersion, newActive, now, false);
	}

	AttemptInProgressResponse projectInProgress(MemberAttempt attempt) {
		List<MemberAttemptItemRow> rows = itemRepository.findByAttempt(attempt.id());
		List<PublishedItemSnapshot> snapshots = rows.stream()
			.map(row -> FrozenItemSnapshot.fromRow(row, objectMapper)).toList();
		List<AttemptAnswerSnapshot> answers = answerRepository.findByAttempt(attempt.id()).stream()
			.map(a -> new AttemptAnswerSnapshot(
				a.itemId(), a.selectedNo(), a.activeElapsedSec(), a.revision()))
			.toList();
		return AttemptProjections.toInProgress(
			attempt.id(), attempt.assignmentId(), attempt.status().name(),
			attempt.version(), attempt.snapshotHash(), null,
			attempt.activeElapsedSec(), attempt.startedAt(),
			snapshots, answers);
	}

	private AttemptResult projectResult(MemberAttempt attempt) {
		List<MemberAttemptItemRow> rows = itemRepository.findByAttempt(attempt.id());
		List<PublishedItemSnapshot> snapshots = rows.stream()
			.map(row -> FrozenItemSnapshot.fromRow(row, objectMapper)).toList();
		Map<UUID, Integer> selected = AttemptProjections.selectedByItemId(
			answerRepository.findByAttempt(attempt.id()).stream()
				.map(answer -> new AttemptAnswerSnapshot(
					answer.itemId(), answer.selectedNo(), answer.activeElapsedSec(),
					answer.revision()))
				.toList());
		Map<UUID, Integer> correctByItem = new LinkedHashMap<>();
		for (PublishedItemSnapshot snapshot : snapshots) {
			correctByItem.put(snapshot.itemId(), snapshot.correctNo());
		}
		AttemptScoring.ScoreResult score = AttemptScoring.score(correctByItem, selected);
		MemberLearningSession session = sessionRepository.findByAttempt(attempt.id())
			.orElseThrow(() -> new IllegalStateException(
				"scored attempt has no learning session " + attempt.id()));
		return AttemptProjections.toResult(
			attempt.id(), attempt.assignmentId(), snapshots.size(), score.correct(),
			score.accuracyRate(), attempt.activeElapsedSec(), attempt.startedAt(),
			attempt.submittedAt(), attempt.scoredAt(), session.submitRecordId(),
			snapshots, selected);
	}

	private MemberAttempt createAttempt(
		UUID studentId, StudentAssignmentRow assignment, List<PublishedItemSnapshot> snapshots
	) {
		Instant now = clock.instant();
		String hash = AttemptSnapshotHash.compute(snapshots);
		MemberAttempt attempt = new MemberAttempt(
			UUID.randomUUID(), studentId, assignment.assignmentId(), assignment.teacherId(),
			MemberAttemptStatus.IN_PROGRESS, 0, hash, snapshots.size(), 0, null,
			now, null, null, null);
		try {
			attemptRepository.insert(attempt);
		}
		catch (DataIntegrityViolationException collision) {
			// 🔴 23505 는 constraint 이름으로만 분기 (메시지 문자열은 로케일 의존).
			// 🔴 발생한 순간 PostgreSQL 트랜잭션이 aborted — 여기서 조회를 더 하면 죽는다.
			//    컨트롤러가 새 트랜잭션에서 recoverAfterRace 로 재조회한다.
			String constraint = ConstraintViolations.constraintNameOf(collision);
			if (!"uq_member_attempts_open".equals(constraint)) {
				throw collision;
			}
			throw new AttemptRaceLostException(studentId, assignment.assignmentId());
		}
		List<MemberAttemptItemRow> rows = new ArrayList<>(snapshots.size());
		for (PublishedItemSnapshot snapshot : snapshots) {
			rows.add(new MemberAttemptItemRow(
				attempt.id(), snapshot.itemId(), snapshot.ordinal(),
				snapshot.stem(), snapshot.passage(),
				optionsJson(snapshot.options()),
				snapshot.correctNo(), snapshot.explanation(),
				snapshot.areaTag(), snapshot.typeTag(), snapshot.skillNodeId()));
		}
		itemRepository.insertAll(rows);
		List<UUID> itemIds = snapshots.stream().map(PublishedItemSnapshot::itemId).toList();
		answerRepository.insertPlaceholders(attempt.id(), itemIds, now);
		eventRepository.insert(attempt.id(), MemberAttemptEventType.STARTED, null, null, now);
		return attempt;
	}

	private int writeAnswers(
		UUID attemptId, Map<UUID, Integer> answers, Map<UUID, Integer> deltas, Instant now
	) {
		Map<UUID, Integer> touched = new LinkedHashMap<>();
		answers.forEach((id, no) -> touched.put(id, 0));
		deltas.forEach((id, sec) -> touched.merge(id, 0, Integer::sum));
		int added = 0;
		for (UUID itemId : touched.keySet()) {
			Integer selectedNo = answers.get(itemId);
			int delta = deltas.getOrDefault(itemId, 0);
			answerRepository.upsert(attemptId, itemId, selectedNo, delta, now);
			added += delta;
		}
		return added;
	}

	private Map<UUID, Integer> optionCountsFrom(List<MemberAttemptItemRow> items) {
		Map<UUID, Integer> counts = new LinkedHashMap<>();
		for (MemberAttemptItemRow row : items) {
			PublishedItemSnapshot snapshot = FrozenItemSnapshot.fromRow(row, objectMapper);
			counts.put(row.itemId(), snapshot.options().size());
		}
		return counts;
	}

	private boolean hasScoredAttempt(UUID studentId, UUID assignmentId) {
		return worksheetQuery.findLatestAttemptSummaries(studentId).stream()
			.filter(summary -> assignmentId.equals(summary.assignmentId()))
			.anyMatch(summary -> summary.status() == MemberAttemptStatus.SCORED
				|| summary.status() == MemberAttemptStatus.SUBMITTED);
	}

	private String optionsJson(List<PublishedItemOption> options) {
		try {
			return objectMapper.writeValueAsString(options);
		}
		catch (RuntimeException failure) {
			throw new IllegalStateException("failed to serialize options", failure);
		}
	}

	/** 시작 결과. 재개면 {@code created=false} → 컨트롤러가 200 을 낸다. */
	public record StartOutcome(AttemptInProgressResponse response, boolean created) {
	}

	/**
	 * 🔴 시작 트랜잭션 안에서 잡히지 않는다 — 시작 트랜잭션은 방금 롤백된다.
	 * 컨트롤러가 이 예외를 잡아 {@link #recoverAfterRace} 로 200 을 낸다.
	 */
	public static final class AttemptRaceLostException extends RuntimeException {

		private final transient UUID studentId;
		private final transient UUID assignmentId;

		private AttemptRaceLostException(UUID studentId, UUID assignmentId) {
			this.studentId = studentId;
			this.assignmentId = assignmentId;
		}

		public UUID studentId() {
			return studentId;
		}

		public UUID assignmentId() {
			return assignmentId;
		}
	}
}
