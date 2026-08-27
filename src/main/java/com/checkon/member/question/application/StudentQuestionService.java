package com.checkon.member.question.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.application.MemberTeacherSummary;
import com.checkon.member.auth.infrastructure.persistence.MemberTeacherRepository;
import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.IdempotencyGuard;
import com.checkon.member.common.persistence.IdempotentOutcome;
import com.checkon.member.common.persistence.IdempotentPayload;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.presentation.CursorPage;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.learning.application.WorksheetTitles;
import com.checkon.member.learning.infrastructure.persistence.StudentWorksheetQueryRepository;
import com.checkon.member.learning.application.WorksheetCursor;
import com.checkon.member.learning.application.WorksheetCursor.InvalidWorksheetCursorException;
import com.checkon.member.learning.domain.StudentAssignmentRow;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptItemRepository;
import com.checkon.member.question.application.dto.CreateQuestionMessageRequest;
import com.checkon.member.question.application.dto.CreateQuestionRequest;
import com.checkon.member.question.application.dto.QuestionMessageResponse;
import com.checkon.member.question.application.dto.StudentQuestionDetailResponse;
import com.checkon.member.question.application.dto.StudentQuestionResponse;
import com.checkon.member.question.domain.QuestionAuthorRole;
import com.checkon.member.question.domain.QuestionMessageRecord;
import com.checkon.member.question.domain.QuestionRecord;
import com.checkon.member.question.domain.QuestionStatus;
import com.checkon.member.question.infrastructure.persistence.MemberQuestionRepository;
import com.checkon.member.question.infrastructure.persistence.TeacherRelationshipReader;

/**
 * 학생 질문 (§3 PR6). {@code POST · GET · GET one · POST message}.
 *
 * <p>🔴 <b>WAITING → ANSWERED 전이는 이 PR 이 만들지 않는다.</b> 강사 답변 API 가 계약에 없다
 * ({@code MEMBER_TEACHER_CONTRACT.md} 로 넘긴다). 공개 API 로는 FOLLOW_UP 에 도달할 수
 * 없다 — 테스트가 리포지토리 레벨에서 상태를 만든다(PR6 §3 마지막 문단).</p>
 *
 * <p>🔴 학생 컨텍스트만 연다. 강사·학부모 세팅 금지(절대 규칙 3). RLS 는
 * {@code member_questions_member_student_*}(V41) 가 학생 self 로 격리한다.</p>
 *
 * <p>🔴 {@code learning} sub-context 참조는 <b>공유 유틸리티</b>({@link WorksheetTitles} ·
 * {@link WorksheetCursor} · {@link StudentWorksheetQueryRepository}) 에 한한다 — 도메인
 * 규칙을 복제하지 않기 위함이며(PR6 §3), G2 는 {@code com.checkon.member.*} 안의 상호 참조는
 * 검사하지 않는다(실측: G2 정규식이 {@code com.checkon.(account|roster|...)} 로 최상위만 본다).</p>
 */
@Service
public class StudentQuestionService {

	public static final String ROUTE_CREATE = "POST /member/students/me/questions";
	public static final String ROUTE_MESSAGE = "POST /member/students/me/questions/{id}/messages";
	public static final int CREATED = 201;
	private static final int LIMIT_MAX = 50;

	private final MemberQuestionRepository questionRepository;
	private final StudentWorksheetQueryRepository worksheetQueryRepository;
	private final MemberAttemptItemRepository attemptItemRepository;
	private final TeacherRelationshipReader teacherRelationshipReader;
	private final MemberTeacherRepository teacherRepository;
	private final IdempotencyGuard idempotencyGuard;
	private final MemberDatabaseContext databaseContext;
	private final MemberQuestionProperties properties;
	private final Clock clock;

	public StudentQuestionService(
		MemberQuestionRepository questionRepository,
		StudentWorksheetQueryRepository worksheetQueryRepository,
		MemberAttemptItemRepository attemptItemRepository,
		TeacherRelationshipReader teacherRelationshipReader,
		MemberTeacherRepository teacherRepository,
		IdempotencyGuard idempotencyGuard,
		MemberDatabaseContext databaseContext,
		MemberQuestionProperties properties,
		Clock clock
	) {
		this.questionRepository = questionRepository;
		this.worksheetQueryRepository = worksheetQueryRepository;
		this.attemptItemRepository = attemptItemRepository;
		this.teacherRelationshipReader = teacherRelationshipReader;
		this.teacherRepository = teacherRepository;
		this.idempotencyGuard = idempotencyGuard;
		this.databaseContext = databaseContext;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public IdempotentOutcome create(
		MemberSubject subject, String idempotencyKey, String rawBody, CreateQuestionRequest request
	) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		validate(request);

		return idempotencyGuard.execute(subject.accountId(), ROUTE_CREATE,
			idempotencyKey, rawBody,
			() -> new IdempotentPayload(CREATED,
				MemberResponse.of(insert(subject, studentId, request))));
	}

	@Transactional(readOnly = true)
	public CursorPage<StudentQuestionResponse> list(
		MemberSubject subject, String cursor, Integer limit
	) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		int pageSize = requireValidLimit(limit);
		OffsetDateTime cursorAt = null;
		UUID cursorId = null;
		if (cursor != null && !cursor.isBlank()) {
			WorksheetCursor decoded = decodeCursor(cursor);
			cursorAt = OffsetDateTime.ofInstant(decoded.publishedAt(), ZoneOffset.UTC);
			cursorId = decoded.assignmentId();
		}
		List<QuestionRecord> rows = questionRepository.findPage(
			studentId, cursorAt, cursorId, pageSize + 1);

		boolean hasNext = rows.size() > pageSize;
		List<QuestionRecord> pageRows = hasNext ? rows.subList(0, pageSize) : rows;
		List<StudentQuestionResponse> items = pageRows.stream()
			.map(row -> assemble(row))
			.toList();
		String nextCursor = null;
		if (hasNext) {
			QuestionRecord last = pageRows.get(pageRows.size() - 1);
			nextCursor = new WorksheetCursor(last.createdAt(), last.id()).encode();
		}
		return new CursorPage<>(items, nextCursor, hasNext);
	}

	@Transactional(readOnly = true)
	public StudentQuestionDetailResponse get(MemberSubject subject, UUID questionId) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		QuestionRecord row = questionRepository.findById(questionId)
			// 남의 질문은 RLS 로 0행 → 404 (계약 §0-1 ④ · 부재와 권한 없음을 구분하지 않는다).
			.orElseThrow(() -> new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"no question matches the given id"));

		List<QuestionMessageResponse> messages = questionRepository.findMessages(questionId)
			.stream()
			.map(StudentQuestionService::toMessage)
			.toList();
		return detail(row, messages);
	}

	@Transactional
	public IdempotentOutcome addMessage(
		MemberSubject subject, UUID questionId, String idempotencyKey, String rawBody,
		CreateQuestionMessageRequest request
	) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		if (request == null || request.content() == null) {
			throw invalidField("content", "content is required");
		}
		String content = request.content();
		if (content.isEmpty() || content.length() > 2000) {
			throw invalidField("content", "content length must be between 1 and 2000");
		}

		return idempotencyGuard.execute(subject.accountId(), ROUTE_MESSAGE,
			idempotencyKey, rawBody,
			() -> new IdempotentPayload(CREATED,
				MemberResponse.of(appendMessage(subject, questionId, content))));
	}

	private void validate(CreateQuestionRequest request) {
		if (request == null) {
			throw invalidField("body", "request body is required");
		}
		if (request.assignmentId() == null) {
			throw invalidField("assignmentId", "assignmentId is required");
		}
		if (request.title() == null || request.title().isEmpty() || request.title().length() > 200) {
			throw invalidField("title", "title length must be between 1 and 200");
		}
		if (request.content() == null || request.content().isEmpty()
			|| request.content().length() > 2000) {
			throw invalidField("content", "content length must be between 1 and 2000");
		}
		// 🔴 itemId 단독 금지 — attempt 없이 문항만 지목하면 saved_problem_set_items 를 다시
		//    봐야 하고 그건 "복사 이후 조회는 member_* 만"(PR5 §4)을 깬다.
		if (request.itemId() != null && request.attemptId() == null) {
			throw invalidField("itemId", "attemptId is required when itemId is present");
		}
	}

	private StudentQuestionResponse insert(
		MemberSubject subject, UUID studentId, CreateQuestionRequest request
	) {
		// 1. 학생 소유 assignment 확인. RLS 정책이 격리한다.
		//    🔴 <b>MemberQuestionRepository.findAssignmentTeacher 를 쓴다</b> — 관계 필터가 없다.
		//    StudentWorksheetQueryRepository.findAssignment 는 relationship IN (ACTIVE, PAUSED)
		//    EXISTS 를 걸어 ENDED 관계면 assignment 자체가 404 로 뭉개진다. 여기서는 422 로
		//    갈리기 위해 관계 확인을 서비스가 직접 한다.
		UUID teacherId = questionRepository
			.findAssignmentTeacher(request.assignmentId(), studentId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"no assignment matches the given id"));

		// 2. 관계 확인 — ACTIVE/PAUSED. ENDED 는 422.
		if (!teacherRelationshipReader.isTeachingStudent(teacherId, studentId)) {
			throw new MemberException(MemberErrorCode.RELATIONSHIP_REQUIRED,
				"teacher is no longer teaching this student");
		}

		// 4. attempt 검증. 자기 attempt 이고 같은 assignment 여야 한다.
		if (request.attemptId() != null) {
			UUID attemptAssignment = questionRepository
				.findAttemptAssignment(request.attemptId(), studentId)
				.orElseThrow(() -> new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
					"no attempt matches the given id"));
			if (!attemptAssignment.equals(request.assignmentId())) {
				throw new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
					"attempt does not belong to the given assignment");
			}
		}

		// 5. item 검증. attempt 안에 있어야 한다.
		if (request.itemId() != null) {
			questionRepository.findItemOrdinal(request.attemptId(), request.itemId())
				.orElseThrow(() -> new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
					"no item matches the given id in this attempt"));
		}

		Instant now = clock.instant();
		QuestionRecord toInsert = new QuestionRecord(
			null, studentId, teacherId, request.assignmentId(),
			request.attemptId(), request.itemId(),
			request.title(), request.content(),
			QuestionStatus.WAITING, 0, now, null);
		UUID id = questionRepository.insertQuestion(toInsert);
		// 6. 첫 메시지 (author='STUDENT'). 정책이 최종 보장한다.
		questionRepository.insertMessage(id, QuestionAuthorRole.STUDENT,
			subject.accountId(), request.content(), now);

		QuestionRecord persisted = questionRepository.findById(id)
			.orElseThrow(() -> new MemberException(MemberErrorCode.INTERNAL,
				"just-inserted question is unreadable"));
		return assemble(persisted);
	}

	private QuestionMessageResponse appendMessage(
		MemberSubject subject, UUID questionId, String content
	) {
		QuestionRecord row = questionRepository.findById(questionId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"no question matches the given id"));

		switch (row.status()) {
			case WAITING -> throw new MemberException(MemberErrorCode.REVISION_CONFLICT,
				"question is waiting for the teacher's answer");
			case ANSWERED -> {
				questionRepository.incrementFollowUp(questionId);
			}
			case FOLLOW_UP -> {
				if (row.followUpCount() >= properties.maxFollowUps()) {
					throw new MemberException(MemberErrorCode.REVISION_CONFLICT,
						"follow-up limit reached");
				}
				questionRepository.incrementFollowUp(questionId);
			}
		}

		Instant now = clock.instant();
		UUID messageId = questionRepository.insertMessage(
			questionId, QuestionAuthorRole.STUDENT, subject.accountId(), content, now);
		return new QuestionMessageResponse(
			messageId, QuestionAuthorRole.STUDENT, content, now);
	}

	private StudentQuestionResponse assemble(QuestionRecord row) {
		return new StudentQuestionResponse(
			row.id(), row.assignmentId(), row.itemId(),
			ordinalOf(row), worksheetTitleOf(row),
			row.title(), row.status(), row.createdAt(), row.answeredAt(),
			teacherOf(row.studentId(), row.teacherId()));
	}

	private StudentQuestionDetailResponse detail(
		QuestionRecord row, List<QuestionMessageResponse> messages
	) {
		return new StudentQuestionDetailResponse(
			row.id(), row.assignmentId(), row.itemId(),
			ordinalOf(row), worksheetTitleOf(row),
			row.title(), row.status(), row.createdAt(), row.answeredAt(),
			teacherOf(row.studentId(), row.teacherId()), row.content(), messages);
	}

	private Integer ordinalOf(QuestionRecord row) {
		if (row.attemptId() == null || row.itemId() == null) {
			return null;
		}
		return questionRepository.findItemOrdinal(row.attemptId(), row.itemId()).orElse(null);
	}

	// 🔴 파생 규칙 정본은 WorksheetTitles 한 곳(learning) 이다 — 두 번째 규칙을 만들지 않는다(PR6 §3).
	private String worksheetTitleOf(QuestionRecord row) {
		StudentAssignmentRow assignment = worksheetQueryRepository
			.findAssignment(row.assignmentId(), row.studentId())
			.orElse(null);
		if (assignment == null) {
			// 관계가 끊긴 뒤 재열람하는 경우. 지어내지 않고 빈 문자열이 아닌 fallback.
			return WorksheetTitles.derive(0, row.createdAt());
		}
		// itemCount 는 여기서 세지 않는다 — 학생용 목록에서 정확도가 이 목적에 필요 없다.
		// 학습지 상세와 다르게, 질문 목록 카드는 학생이 이미 열어본 학습지의 제목만 필요하다.
		int itemCount = attemptItemRowsCount(row);
		return WorksheetTitles.derive(itemCount, assignment.publishedAt());
	}

	private int attemptItemRowsCount(QuestionRecord row) {
		if (row.attemptId() == null) {
			return 0;
		}
		return attemptItemRepository.findByAttempt(row.attemptId()).size();
	}

	private MemberTeacherSummary teacherOf(UUID studentId, UUID teacherId) {
		// 자기 강사 목록에서 이름을 찾는다. 없으면(관계가 끊긴 케이스) 빈 문자열.
		List<MemberTeacherSummary> teachers = teacherRepository.findForStudent(studentId);
		return teachers.stream()
			.filter(teacher -> teacher.teacherId().equals(teacherId))
			.findFirst()
			.orElse(new MemberTeacherSummary(teacherId, "", null));
	}

	private static QuestionMessageResponse toMessage(QuestionMessageRecord record) {
		return new QuestionMessageResponse(
			record.id(), record.authorRole(), record.content(), record.publishedAt());
	}

	private int requireValidLimit(Integer limit) {
		int pageSize = limit == null ? LIMIT_MAX : limit;
		if (pageSize < 1 || pageSize > LIMIT_MAX) {
			throw invalidField("limit", "limit must be between 1 and 50");
		}
		return pageSize;
	}

	private WorksheetCursor decodeCursor(String cursor) {
		try {
			return WorksheetCursor.decode(cursor);
		}
		catch (InvalidWorksheetCursorException exception) {
			throw invalidField("cursor", "cursor is malformed");
		}
	}

	private static MemberException invalidField(String field, String message) {
		return new MemberException(MemberErrorCode.INVALID_REQUEST, message,
			List.of(new FieldViolation(field, message)));
	}
}
