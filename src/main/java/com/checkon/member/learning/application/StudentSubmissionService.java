package com.checkon.member.learning.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.IdempotencyGuard;
import com.checkon.member.common.persistence.IdempotentOutcome;
import com.checkon.member.common.persistence.IdempotentPayload;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.learning.LearningRecordWriter;
import com.checkon.member.integration.learning.LearningRecordWriter.SolveRow;
import com.checkon.member.integration.problem.PublishedItemOption;
import com.checkon.member.integration.problem.PublishedItemSnapshot;
import com.checkon.member.integration.problem.ProblemAssignmentResponseWriter;
import com.checkon.member.integration.problem.ProblemAssignmentResponseWriter.ScoredResponseRow;
import com.checkon.member.learning.application.AttemptScoring.ScoreResult;
import com.checkon.member.learning.application.dto.AttemptSubmissionRequest;
import com.checkon.member.learning.domain.MemberAttempt;
import com.checkon.member.learning.domain.MemberAttemptAnswer;
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
 * 제출 트랜잭션 (PR5 §7 · 분기표 §1). 🔴 <b>한 트랜잭션 안에서</b> 다음을 순서대로 한다:
 *
 * <ol>
 *   <li>{@code member_attempts} SELECT FOR UPDATE — 두 탭 동시 제출을 직렬화</li>
 *   <li>상태·낙관락 검사 — {@code IN_PROGRESS} 아니면 409, {@code baseVersion} 어긋나면 409</li>
 *   <li>{@code answers} 반영(있으면 UPSERT 로 저장 답안을 최신화)</li>
 *   <li>결정론 채점 — {@link AttemptScoring} · 🔴 실 LLM 호출 0회</li>
 *   <li>IN_PROGRESS → SUBMITTED → SCORED (두 UPDATE + 이벤트 2행)</li>
 *   <li>{@code problem_assignment_responses} 문항당 1행 INSERT — 승우님 결과 정본</li>
 *   <li>{@code learning_records} SOLVE·SUBMIT 행 INSERT — 강사 위험탐지가 여기를 읽는다</li>
 *   <li>{@code member_learning_sessions} 1행 — 학부모·강사 요약</li>
 * </ol>
 *
 * <p>🔴 <b>{@code Idempotency-Key} 는 필수</b>다. PR4 의 {@link IdempotencyGuard} 를 <b>재사용</b>한다 —
 * 자문 잠금 + 저장 응답 재생으로 두 번째 요청이 채점을 다시 돌지 않는다.</p>
 *
 * <p>🔴 <b>§6-4-3 표대로 컨텍스트를 전부 연다.</b> 계정만 열면 예외가 아니라 <b>0행</b>이다 —
 * 학생 컨텍스트 없이 {@code learning_records} 정책은 통과하지 못한다. 이 서비스는 진입에서
 * 계정·학생을 세팅하고, 트랜잭션이 끝날 때 PostgreSQL 이 세션 변수를 자동 해제한다.</p>
 *
 * <p>🔴 <b>MB-05 CONFIRMED — 미응답 제출을 허용</b>한다({@code require-complete-submission=false}).
 * {@code SUBMISSION_INCOMPLETE}(422) 는 어떤 경로에서도 나오지 않는다.</p>
 *
 * <p>🔴 ✅ <b>MB-28 CONFIRMED · 둘 다 쓴다</b>(2026-08-26). {@code learning_records}(사건 단위 —
 * SOLVE/SUBMIT, {@code item_id}·{@code chosen_no} 없음) 와 {@code problem_assignment_responses}
 * (문항 단위 — {@code area_tag}·{@code type_tag}·{@code skill_node_id} 전부 NOT NULL) 는 서로
 * <b>대체 불가</b>다. 자세한 근거는 {@link LearningRecordWriter} 클래스 주석.</p>
 */
@Service
public class StudentSubmissionService {

	public static final String ROUTE_KEY =
		"POST /member/students/me/attempts/{attemptId}/submission";
	public static final int OK = 200;

	private final StudentWorksheetQueryRepository worksheetQuery;
	private final MemberAttemptRepository attemptRepository;
	private final MemberAttemptItemRepository itemRepository;
	private final MemberAttemptAnswerRepository answerRepository;
	private final MemberAttemptEventRepository eventRepository;
	private final MemberLearningSessionRepository sessionRepository;
	private final ProblemAssignmentResponseWriter responseWriter;
	private final LearningRecordWriter learningRecordWriter;
	private final IdempotencyGuard idempotencyGuard;
	private final MemberDatabaseContext databaseContext;
	private final AttemptProgressValidator progressValidator;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public StudentSubmissionService(
		StudentWorksheetQueryRepository worksheetQuery,
		MemberAttemptRepository attemptRepository,
		MemberAttemptItemRepository itemRepository,
		MemberAttemptAnswerRepository answerRepository,
		MemberAttemptEventRepository eventRepository,
		MemberLearningSessionRepository sessionRepository,
		ProblemAssignmentResponseWriter responseWriter,
		LearningRecordWriter learningRecordWriter,
		IdempotencyGuard idempotencyGuard,
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
		this.responseWriter = responseWriter;
		this.learningRecordWriter = learningRecordWriter;
		this.idempotencyGuard = idempotencyGuard;
		this.databaseContext = databaseContext;
		this.progressValidator = new AttemptProgressValidator(properties.maxProgressDeltaSeconds());
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public IdempotentOutcome submit(
		MemberSubject subject,
		UUID attemptId,
		String idempotencyKey,
		String rawBody,
		AttemptSubmissionRequest request
	) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		return idempotencyGuard.execute(subject.accountId(), ROUTE_KEY, idempotencyKey, rawBody,
			() -> new IdempotentPayload(OK,
				MemberResponse.of(process(studentId, attemptId, request))));
	}

	private AttemptResult process(
		UUID studentId, UUID attemptId, AttemptSubmissionRequest request
	) {
		validate(request);
		MemberAttempt attempt = attemptRepository.lockById(attemptId)
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.RESOURCE_NOT_FOUND, "attempt not found"));
		if (attempt.status() != MemberAttemptStatus.IN_PROGRESS) {
			throw new MemberException(MemberErrorCode.ATTEMPT_ALREADY_SUBMITTED,
				"attempt already submitted");
		}
		if (request.baseVersion() != attempt.version()) {
			throw new MemberException(MemberErrorCode.REVISION_CONFLICT,
				"base version mismatch",
				List.of(new FieldViolation("currentVersion",
					Integer.toString(attempt.version()))));
		}

		List<MemberAttemptItemRow> itemRows = itemRepository.findByAttempt(attemptId);
		List<PublishedItemSnapshot> snapshots = itemRows.stream()
			.map(row -> FrozenItemSnapshot.fromRow(row, objectMapper)).toList();
		Map<UUID, PublishedItemSnapshot> snapshotByItemId = new LinkedHashMap<>();
		for (PublishedItemSnapshot snapshot : snapshots) {
			snapshotByItemId.put(snapshot.itemId(), snapshot);
		}

		Map<UUID, Integer> answers = request.answers() == null ? Map.of() : request.answers();
		Map<UUID, Integer> deltas = request.activeElapsedSecondsDelta() == null
			? Map.of() : request.activeElapsedSecondsDelta();
		Map<UUID, Integer> optionCounts = new LinkedHashMap<>();
		for (PublishedItemSnapshot snapshot : snapshots) {
			optionCounts.put(snapshot.itemId(), snapshot.options().size());
		}
		progressValidator.validateBody(
			answers, deltas, List.copyOf(snapshotByItemId.keySet()), optionCounts);

		Instant now = clock.instant();
		int addedSeconds = mergePendingAnswers(attempt.id(), answers, deltas, now);
		int totalActiveElapsedSec = attempt.activeElapsedSec() + addedSeconds;

		Map<UUID, Integer> selected = collectSelected(attemptId);
		Map<UUID, Integer> correctByItemId = new LinkedHashMap<>();
		for (PublishedItemSnapshot snapshot : snapshots) {
			// 시작 시점의 gradability check 가 통과해서 여기 왔다 — correctNo 는 non-null 이다.
			correctByItemId.put(snapshot.itemId(), snapshot.correctNo());
		}
		ScoreResult score = AttemptScoring.score(correctByItemId, selected);

		int submittedVersion = attempt.version() + 1;
		int scoredVersion = attempt.version() + 2;
		int submittedRows = attemptRepository.markSubmitted(
			attemptId, submittedVersion, totalActiveElapsedSec, now);
		if (submittedRows != 1) {
			throw new MemberException(MemberErrorCode.ATTEMPT_ALREADY_SUBMITTED,
				"attempt transitioned to another state during submission");
		}
		eventRepository.insert(attemptId, MemberAttemptEventType.SUBMITTED, null, null, now);
		int scoredRows = attemptRepository.markScored(attemptId, scoredVersion, now);
		if (scoredRows != 1) {
			throw new IllegalStateException(
				"scored transition failed for attempt " + attemptId
					+ " — SUBMITTED row disappeared inside the same transaction");
		}
		eventRepository.insert(attemptId, MemberAttemptEventType.SCORED, null, null, now);

		StudentAssignmentRow assignment = worksheetQuery
			.findAssignment(attempt.assignmentId(), studentId)
			.orElseThrow(() -> new IllegalStateException(
				"assignment vanished from student view during submission"));
		String assignmentTitle = WorksheetTitles.derive(snapshots.size(), assignment.publishedAt());

		Map<UUID, Integer> activeSecondsByItem = activeSecondsByItem(attemptId);
		writeGradedResponses(studentId, assignment, snapshots, selected, now);
		UUID learningRecordId = learningRecordWriter.writeAll(
			attemptId, attempt.teacherId(), studentId, now,
			assignmentTitle, totalActiveElapsedSec,
			solvesFor(snapshots, selected, correctByItemId, activeSecondsByItem));
		sessionRepository.insert(new MemberLearningSession(
			UUID.randomUUID(), attemptId, studentId, attempt.teacherId(), assignment.assignmentId(),
			assignmentTitle, snapshots.size(), score.correct(), totalActiveElapsedSec,
			learningRecordId, now));

		return AttemptProjections.toResult(
			attemptId, assignment.assignmentId(), snapshots.size(), score.correct(),
			score.accuracyRate(), totalActiveElapsedSec, attempt.startedAt(),
			now, now, learningRecordId, snapshots, selected);
	}

	private void validate(AttemptSubmissionRequest request) {
		if (request == null || request.baseVersion() == null) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"baseVersion is required",
				List.of(new FieldViolation("baseVersion", "must not be null")));
		}
	}

	/**
	 * 🔴 last-minute {@code answers} 를 저장 답안에 반영한다. progress 와 <b>같은 규칙</b>을 쓴다 —
	 * itemId 소속과 selectedNo 범위를 검증한다. delta 는 여기서 더하지 않는다(제출 body 는 시간을
	 * 넘기지 않는다). 미응답(null) 은 명시 초기화가 아니라 「변경 없음」이다.
	 */
	private int mergePendingAnswers(
		UUID attemptId,
		Map<UUID, Integer> answers,
		Map<UUID, Integer> deltas,
		Instant now
	) {
		Map<UUID, Integer> touched = new LinkedHashMap<>();
		answers.keySet().forEach(itemId -> touched.put(itemId, 0));
		deltas.keySet().forEach(itemId -> touched.put(itemId, 0));
		int addedSeconds = 0;
		for (UUID itemId : touched.keySet()) {
			int delta = deltas.getOrDefault(itemId, 0);
			answerRepository.upsert(attemptId, itemId, answers.get(itemId), delta, now);
			addedSeconds += delta;
		}
		return addedSeconds;
	}

	private Map<UUID, Integer> collectSelected(UUID attemptId) {
		Map<UUID, Integer> selected = new LinkedHashMap<>();
		for (MemberAttemptAnswer answer : answerRepository.findByAttempt(attemptId)) {
			if (answer.selectedNo() != null) {
				selected.put(answer.itemId(), answer.selectedNo());
			}
		}
		return selected;
	}

	private Map<UUID, Integer> activeSecondsByItem(UUID attemptId) {
		Map<UUID, Integer> seconds = new LinkedHashMap<>();
		for (MemberAttemptAnswer answer : answerRepository.findByAttempt(attemptId)) {
			seconds.put(answer.itemId(), answer.activeElapsedSec());
		}
		return seconds;
	}

	private void writeGradedResponses(
		UUID studentId,
		StudentAssignmentRow assignment,
		List<PublishedItemSnapshot> snapshots,
		Map<UUID, Integer> selected,
		Instant now
	) {
		List<ScoredResponseRow> rows = new ArrayList<>();
		for (PublishedItemSnapshot snapshot : snapshots) {
			Integer chosen = selected.get(snapshot.itemId());
			if (chosen == null) {
				// 🔴 미응답은 problem_assignment_responses.chosen_no(NOT NULL)를 만족시킬 수 없다.
				//    MB-05 CONFIRMED 로 학생에게는 허용하지만, 미응답은 승우님 진단 테이블에
				//    기록하지 않는다 — 학생의 학습 원본은 learning_records 로만 남긴다.
				continue;
			}
			int correctNo = snapshot.correctNo();
			boolean correct = chosen == correctNo;
			String misconceptionTag = correct ? null : misconceptionOf(snapshot, chosen);
			if (!correct && misconceptionTag == null) {
				// 🔴 ck_problem_response_misconception 이 거절한다 — 지어내지 말고 어떤 문항이
				//    태그 없이 왔는지 남기고 롤백한다.
				throw new MemberException(MemberErrorCode.WORKSHEET_NOT_GRADABLE,
					"selected option has no misconceptionTag",
					new WorksheetNotGradableDetails(List.of(snapshot.itemId()),
						List.of("misconceptionTag")));
			}
			rows.add(new ScoredResponseRow(
				assignment.teacherId(), studentId, snapshot.itemId(),
				chosen, correctNo, correct,
				snapshot.areaTag(), snapshot.typeTag(), snapshot.skillNodeId(),
				misconceptionTag));
		}
		if (rows.isEmpty()) {
			return;
		}
		responseWriter.insertAll(assignment.assignmentId(), assignment.problemSetId(), rows, now);
	}

	private String misconceptionOf(PublishedItemSnapshot snapshot, int chosenNo) {
		for (PublishedItemOption option : snapshot.options()) {
			if (option.position() == chosenNo) {
				return option.misconceptionTag();
			}
		}
		return null;
	}

	private List<SolveRow> solvesFor(
		List<PublishedItemSnapshot> snapshots,
		Map<UUID, Integer> selected,
		Map<UUID, Integer> correctByItemId,
		Map<UUID, Integer> activeSecondsByItem
	) {
		List<SolveRow> rows = new ArrayList<>();
		for (PublishedItemSnapshot snapshot : snapshots) {
			Integer chosen = selected.get(snapshot.itemId());
			if (chosen == null) {
				continue;
			}
			int correctNo = correctByItemId.getOrDefault(snapshot.itemId(), 0);
			boolean correct = chosen == correctNo;
			int seconds = activeSecondsByItem.getOrDefault(snapshot.itemId(), 0);
			rows.add(new SolveRow(snapshot.itemId(), correct, seconds));
		}
		return rows;
	}

}
