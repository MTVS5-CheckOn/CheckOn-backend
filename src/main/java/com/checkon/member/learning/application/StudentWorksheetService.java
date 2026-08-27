package com.checkon.member.learning.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.problem.PublishedWorksheetAdapter;
import com.checkon.member.integration.account.StudentAliasReader;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;
import com.checkon.member.learning.application.WorksheetCursor.InvalidWorksheetCursorException;
import com.checkon.member.learning.application.dto.WorksheetDetailResponse;
import com.checkon.member.learning.application.dto.WorksheetDetailResponse.ItemBreakdownRow;
import com.checkon.member.learning.application.dto.WorksheetSummaryResponse;
import com.checkon.member.learning.application.dto.StudentHomeResponse;
import com.checkon.member.learning.application.dto.StudentHomeResponse.StudentWeakness;
import com.checkon.member.learning.domain.MemberAttemptStatus;
import com.checkon.member.learning.domain.StudentAttemptSummary;
import com.checkon.member.learning.domain.StudentAssignmentPageRow;
import com.checkon.member.learning.domain.StudentAssignmentRow;
import com.checkon.member.learning.infrastructure.persistence.StudentWorksheetQueryRepository;
import com.checkon.member.common.presentation.CursorPage;

/**
 * 학습지 목록·상세 조회. {@link StudentWorksheetQueryRepository} 위에 <b>스코프 전환</b>과
 * {@code itemCount} 파생을 얹는다.
 *
 * <p>🔴 <b>한 트랜잭션 안에서</b> 학생 컨텍스트를 열고 SQL 로 목록을 얻은 뒤, 각 행마다
 * {@link MemberDatabaseContext#withVerifiedProblemSetScope} 로 스코프를 여닫아 문항 수를 센다.
 * 커넥션·트랜잭션이 하나여서 왕복은 페이지 크기에 비례할 뿐 늘지 않는다(MB-39).</p>
 *
 * <p>🔴 강사 요약({@link TeacherSummaryView}) 은 {@code teacher_profiles}(RLS 밖) 를 읽어 채우고,
 * {@code subject}·{@code academyName} 은 원본 정책이 없어 항상 {@code null} 이다(계약).</p>
 *
 * <p>🔴 {@code accuracyRate} 는 S4(제출 트랜잭션) 이전엔 항상 {@code null} 이다.
 * {@code SCORED} 이더라도 지어내지 않는다 — 원천 계산은 {@code problem_assignment_responses}
 * (승우님 테이블) 에서 뽑아 오는 것이 S4 몫이다.</p>
 */
@Service
public class StudentWorksheetService {

	/** 계약 {@code Limit} 파라미터의 상한. 계약 §0-5 「>50 → 400」 을 여기서 강제한다. */
	static final int MAX_LIMIT = 50;
	static final int DEFAULT_LIMIT = 20;

	private final StudentWorksheetQueryRepository queryRepository;
	private final PublishedWorksheetAdapter publishedWorksheet;
	private final RosterRelationshipPort roster;
	private final MemberDatabaseContext databaseContext;
	private final StudentAliasReader studentAliasReader;
	private final MemberHomeProperties homeProperties;
	private final Clock clock;

	public StudentWorksheetService(
		StudentWorksheetQueryRepository queryRepository,
		PublishedWorksheetAdapter publishedWorksheet,
		RosterRelationshipPort roster,
		MemberDatabaseContext databaseContext,
		StudentAliasReader studentAliasReader,
		MemberHomeProperties homeProperties,
		Clock clock
	) {
		this.queryRepository = queryRepository;
		this.publishedWorksheet = publishedWorksheet;
		this.roster = roster;
		this.databaseContext = databaseContext;
		this.studentAliasReader = studentAliasReader;
		this.homeProperties = homeProperties;
		this.clock = clock;
	}

	/** {@code GET /member/students/me/home}. 없음은 null/빈 배열이며 오류가 아니다. */
	@Transactional(readOnly = true)
	public StudentHomeResponse getHome(MemberSubject subject) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);
		String studentName = studentAliasReader.find(studentId).orElse("");
		Map<UUID, StudentAttemptSummary> attempts = new java.util.LinkedHashMap<>();
		List<StudentAttemptSummary> latestAttempts =
			queryRepository.findLatestAttemptSummaries(studentId);
		for (StudentAttemptSummary summary : latestAttempts) {
			attempts.put(summary.assignmentId(), summary);
		}
		LocalDate today = clock.instant().atZone(homeProperties.zone()).toLocalDate();
		WorksheetSummaryResponse continuing = null;
		List<WorksheetSummaryResponse> todayWorksheets = new ArrayList<>();
		List<StudentAssignmentRow> assignments =
			queryRepository.findAssignmentsByStudent(studentId);
		for (StudentAssignmentRow assignment : assignments) {
			StudentAttemptSummary attempt = attempts.get(assignment.assignmentId());
			StudentAssignmentPageRow row = new StudentAssignmentPageRow(
				assignment.assignmentId(), assignment.problemSetId(), assignment.teacherId(),
				assignment.publishedAt(), attempt == null ? null : attempt.attemptId(),
				attempt == null ? null : attempt.status());
			WorksheetSummaryResponse summary = buildSummary(studentId, row);
			if (continuing == null && attempt != null
				&& attempt.status() == MemberAttemptStatus.IN_PROGRESS) {
				continuing = summary;
			}
			LocalDate published = assignment.publishedAt()
				.atZone(homeProperties.zone()).toLocalDate();
			if (today.equals(published)) {
				todayWorksheets.add(summary);
			}
		}
		return new StudentHomeResponse(
			studentName, continuing, List.copyOf(todayWorksheets), StudentWeakness.noData());
	}

	/** {@code GET /member/students/me/worksheets}. */
	@Transactional(readOnly = true)
	public CursorPage<WorksheetSummaryResponse> listWorksheets(
		MemberSubject subject, String cursorText, Integer limitParam, String statusFilter
	) {
		int limit = validateLimit(limitParam);
		String status = validateStatusFilter(statusFilter);
		WorksheetCursor cursor = parseCursor(cursorText);

		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		List<StudentAssignmentPageRow> raw = queryRepository.findAssignmentPage(
			studentId,
			cursor == null ? null : java.time.OffsetDateTime.ofInstant(
				cursor.publishedAt(), java.time.ZoneOffset.UTC),
			cursor == null ? null : cursor.assignmentId(),
			limit + 1
		);

		List<WorksheetSummaryResponse> pageItems = new ArrayList<>();
		boolean hasNext = false;
		String nextCursor = null;
		for (StudentAssignmentPageRow row : raw) {
			if (pageItems.size() == limit) {
				hasNext = true;
				break;
			}
			WorksheetSummaryResponse summary = buildSummary(studentId, row);
			if (status != null && !status.equals(summary.status())) {
				continue;
			}
			pageItems.add(summary);
		}
		if (hasNext && !pageItems.isEmpty()) {
			WorksheetSummaryResponse last = pageItems.get(pageItems.size() - 1);
			nextCursor = new WorksheetCursor(last.publishedAt(), last.assignmentId()).encode();
		}
		return new CursorPage<>(pageItems, nextCursor, hasNext);
	}

	/** {@code GET /member/students/me/worksheets/{assignmentId}}. */
	@Transactional(readOnly = true)
	public WorksheetDetailResponse getWorksheet(MemberSubject subject, UUID assignmentId) {
		UUID studentId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentId);

		StudentAssignmentRow assignment = queryRepository
			.findAssignment(assignmentId, studentId)
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.RESOURCE_NOT_FOUND, "worksheet not found"));

		int itemCount = databaseContext.withVerifiedProblemSetScope(
			studentId, assignment.problemSetId(),
			() -> publishedWorksheet.countItems(assignment.problemSetId()));

		String status = latestStatusFor(studentId, assignmentId);
		UUID latestAttemptId = latestAttemptIdFor(studentId, assignmentId);
		TeacherSummaryView teacher = teacherOf(assignment.teacherId());
		String title = WorksheetTitles.derive(itemCount, assignment.publishedAt());

		// 🔴 itemBreakdown 은 빈 배열이다(MB-41) — 개별 항목의 areaTag·typeTag 가 required 인데
		//    상세는 문항 본문·정답을 내려보내지 않는 규약이라 스코프를 열어 세는 것도 회피한다.
		return new WorksheetDetailResponse(
			assignment.assignmentId(), title, null, itemCount, null, status,
			assignment.publishedAt(), teacher, latestAttemptId, null, null, List.of());
	}

	private WorksheetSummaryResponse buildSummary(UUID studentId, StudentAssignmentPageRow row) {
		int itemCount = databaseContext.withVerifiedProblemSetScope(
			studentId, row.problemSetId(),
			() -> publishedWorksheet.countItems(row.problemSetId()));
		String status = WorksheetStatuses.deriveFrom(row.latestAttemptStatus());
		TeacherSummaryView teacher = teacherOf(row.teacherId());
		String title = WorksheetTitles.derive(itemCount, row.publishedAt());
		return new WorksheetSummaryResponse(
			row.assignmentId(), title, null, itemCount, null, status,
			row.publishedAt(), teacher, row.latestAttemptId(), null);
	}

	private TeacherSummaryView teacherOf(UUID teacherId) {
		return roster.findTeacherSummary(teacherId)
			.orElseGet(() -> TeacherSummaryView.of(teacherId, ""));
	}

	private String latestStatusFor(UUID studentId, UUID assignmentId) {
		return queryRepository.findLatestAttemptSummaries(studentId).stream()
			.filter(summary -> assignmentId.equals(summary.assignmentId()))
			.map(summary -> WorksheetStatuses.deriveFrom(summary.status()))
			.findFirst()
			.orElse(WorksheetStatuses.NEW);
	}

	private UUID latestAttemptIdFor(UUID studentId, UUID assignmentId) {
		return queryRepository.findLatestAttemptSummaries(studentId).stream()
			.filter(summary -> assignmentId.equals(summary.assignmentId()))
			.map(summary -> summary.attemptId())
			.findFirst()
			.orElse(null);
	}

	private int validateLimit(Integer limitParam) {
		int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
		if (limit < 1 || limit > MAX_LIMIT) {
			// 🔴 조용히 깎지 않는다 — 계약 §0-5 대로 400 을 낸다.
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"limit must be between 1 and " + MAX_LIMIT,
				List.of(new FieldViolation("limit",
					"must be between 1 and " + MAX_LIMIT)));
		}
		return limit;
	}

	private String validateStatusFilter(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		if (!WorksheetStatuses.NEW.equals(status)
			&& !WorksheetStatuses.IN_PROGRESS.equals(status)
			&& !WorksheetStatuses.COMPLETED.equals(status)) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"status must be NEW|IN_PROGRESS|COMPLETED",
				List.of(new FieldViolation("status",
					"must be NEW|IN_PROGRESS|COMPLETED")));
		}
		return status;
	}

	private WorksheetCursor parseCursor(String cursorText) {
		if (cursorText == null || cursorText.isBlank()) {
			return null;
		}
		try {
			return WorksheetCursor.decode(cursorText);
		}
		catch (InvalidWorksheetCursorException exception) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"cursor is malformed",
				List.of(new FieldViolation("cursor", exception.getMessage())));
		}
	}
}
