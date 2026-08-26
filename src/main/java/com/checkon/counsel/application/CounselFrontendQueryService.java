package com.checkon.counsel.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.counsel.application.CreateCounselDraftCommand.Fact;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.infrastructure.persistence.CounselFrontendQueryRepository;
import com.checkon.counsel.infrastructure.persistence.CounselFrontendQueryRepository.CommunicationRow;
import com.checkon.counsel.infrastructure.persistence.CounselFrontendQueryRepository.GuardianRow;
import com.checkon.counsel.infrastructure.persistence.CounselFrontendQueryRepository.InquiryRow;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class CounselFrontendQueryService {

	public static final int MAX_PAGE_SIZE = 100;
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
	private static final TypeReference<List<Fact>> FACT_LIST = new TypeReference<>() { };

	private final CounselFrontendQueryRepository queries;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;

	public CounselFrontendQueryService(
		CounselFrontendQueryRepository queries,
		TeacherTenantDatabaseContext tenantContext,
		ObjectMapper objectMapper
	) {
		this.queries = queries;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public GuardianPage guardians(AuthenticatedAccount principal, int page, int size) {
		UUID teacherId = setTenantScope(principal);
		validatePage(page, size);
		long total = queries.countGuardians(teacherId);
		List<GuardianView> content = queries.findGuardians(teacherId, page, size).stream()
			.map(row -> toGuardianView(teacherId, row))
			.toList();
		return new GuardianPage(content, page, size, total);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CommunicationPage communications(
		AuthenticatedAccount principal, UUID parentId, int page, int size
	) {
		UUID teacherId = setTenantScope(principal);
		validatePage(page, size);
		if (parentId == null || !queries.hasActiveGuardian(teacherId, parentId)) {
			throw GuardianLabelSuggestionException.targetNotFound();
		}
		long total = queries.countCommunications(teacherId, parentId);
		return new CommunicationPage(
			queries.findCommunications(teacherId, parentId, page, size).stream()
				.map(CounselFrontendQueryService::toCommunicationView)
				.toList(),
			page, size, total
		);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public InquiryPage inquiries(AuthenticatedAccount principal, int page, int size) {
		UUID teacherId = setTenantScope(principal);
		validatePage(page, size);
		long total = queries.countInquiries(teacherId);
		return new InquiryPage(
			queries.findInquiries(teacherId, page, size).stream().map(this::toInquiryView).toList(),
			page, size, total
		);
	}

	private GuardianView toGuardianView(UUID teacherId, GuardianRow row) {
		return new GuardianView(
			row.parentId(), null,
			queries.findLinkedStudents(teacherId, row.parentId()).stream()
				.map(student -> new LinkedStudentView(
					student.studentId(), student.studentName(), student.classId(), student.className()
				)).toList(),
			queries.findCurrentLabels(teacherId, row.parentId()).stream()
				.map(label -> new CurrentLabelView(label.axis(), label.value(), label.updatedAt())).toList(),
			row.communicationCount(), row.latestCommunicationAt(),
			row.latestTopic() == null ? null : new LatestInquiryView(
				CounselTopic.fromWireValue(row.latestTopic()),
				CounselUrgency.fromWireValue(row.latestUrgency()),
				row.latestJobId(), row.latestJobPhase()
			)
		);
	}

	private static CommunicationView toCommunicationView(CommunicationRow row) {
		return new CommunicationView(
			row.recordId(), row.direction(), row.occurredAt(), row.body(),
			row.studentId(), row.studentName(), row.inquiryRef(),
			CounselTopic.fromWireValue(row.topic()), CounselUrgency.fromWireValue(row.urgency()),
			row.actuallySent(), row.jobId(), row.jobPhase()
		);
	}

	private InquiryView toInquiryView(InquiryRow row) {
		return new InquiryView(
			row.inquiryRef(), row.parentId(), row.studentId(), row.studentName(),
			row.classId(), row.className(), CounselTopic.fromWireValue(row.topic()),
			CounselUrgency.fromWireValue(row.urgency()), row.receivedAt(), row.rawText(),
			readJson(row.labelsJson(), STRING_LIST), row.periodLabel(), readJson(row.factsJson(), FACT_LIST),
			row.jobId() != null, row.jobId(), row.jobPhase()
		);
	}

	private <T> T readJson(String json, TypeReference<T> type) {
		try {
			return objectMapper.readValue(json, type);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("stored counsel inquiry context is invalid", exception);
		}
	}

	private UUID setTenantScope(AuthenticatedAccount principal) {
		if (principal == null || principal.role() != AccountRole.TEACHER || principal.teacherProfileId() == null) {
			throw CounselException.invalidPrincipal();
		}
		UUID teacherId = principal.teacherProfileId();
		tenantContext.setCurrentTeacher(teacherId);
		return teacherId;
	}

	private static void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw CounselException.invalidRequest("invalid page request");
		}
	}

	public record GuardianPage(List<GuardianView> content, int page, int size, long totalElements) { }
	public record GuardianView(
		UUID parentId, String displayName, List<LinkedStudentView> students, List<CurrentLabelView> labels,
		long communicationCount, java.time.Instant latestCommunicationAt, LatestInquiryView latestInquiry
	) { }
	public record LinkedStudentView(UUID studentId, String studentName, UUID classId, String className) { }
	public record CurrentLabelView(String axis, String value, java.time.Instant updatedAt) { }
	public record LatestInquiryView(CounselTopic topic, CounselUrgency urgency, String jobId, String jobPhase) { }
	public record CommunicationPage(List<CommunicationView> content, int page, int size, long totalElements) { }
	public record CommunicationView(
		String recordId, String direction, java.time.Instant occurredAt, String body,
		UUID studentId, String studentName, String inquiryRef, CounselTopic topic, CounselUrgency urgency,
		boolean actuallySent, String jobId, String jobPhase
	) { }
	public record InquiryPage(List<InquiryView> content, int page, int size, long totalElements) { }
	public record InquiryView(
		String inquiryRef, UUID parentId, UUID studentId, String studentName,
		UUID classId, String className, CounselTopic topic, CounselUrgency urgency,
		java.time.Instant receivedAt, String rawText, List<String> labels, String periodLabel,
		List<Fact> facts, boolean draftJobCreated, String jobId, String jobPhase
	) { }
}
