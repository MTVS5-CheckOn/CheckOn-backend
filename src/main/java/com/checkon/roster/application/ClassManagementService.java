package com.checkon.roster.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.roster.domain.ClassGroup;
import com.checkon.roster.domain.ClassGroupStatus;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.infrastructure.persistence.ClassEnrollmentRepository;
import com.checkon.roster.infrastructure.persistence.ClassGroupQueryRepository;
import com.checkon.roster.infrastructure.persistence.ClassGroupQueryRepository.Row;
import com.checkon.roster.infrastructure.persistence.ClassGroupRepository;

@Service
public class ClassManagementService {
	public static final int DEFAULT_PAGE_SIZE = 20;
	public static final int MAX_PAGE_SIZE = 100;

	private final ClassGroupRepository classGroups;
	private final ClassEnrollmentRepository enrollments;
	private final ClassGroupQueryRepository queries;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public ClassManagementService(
		ClassGroupRepository classGroups,
		ClassEnrollmentRepository enrollments,
		ClassGroupQueryRepository queries,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.classGroups = classGroups;
		this.enrollments = enrollments;
		this.queries = queries;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ClassPage list(AuthenticatedAccount principal, int page, int size) {
		UUID teacherId = setTenantScope(principal);
		validatePage(page, size);
		var result = queries.findActive(teacherId, page, size);
		long totalPages = result.totalElements() == 0
			? 0
			: ((result.totalElements() - 1) / size) + 1;
		return new ClassPage(
			result.content().stream().map(ClassManagementService::toView).toList(),
			page,
			size,
			result.totalElements(),
			totalPages
		);
	}

	@Transactional
	public ClassView create(
		AuthenticatedAccount principal,
		String name,
		String subject,
		String memo
	) {
		UUID teacherId = setTenantScope(principal);
		try {
			ClassGroup saved = classGroups.saveAndFlush(ClassGroup.create(
				teacherId, name, subject, memo, clock.instant()
			));
			return toView(saved, 0);
		}
		catch (IllegalArgumentException exception) {
			throw invalidRequest(exception);
		}
	}

	@Transactional(readOnly = true)
	public ClassView detail(AuthenticatedAccount principal, UUID classGroupId) {
		UUID teacherId = setTenantScope(principal);
		if (classGroupId == null) {
			throw notFound();
		}
		return queries.findById(teacherId, classGroupId)
			.map(ClassManagementService::toView)
			.orElseThrow(ClassManagementService::notFound);
	}

	@Transactional
	public ClassView update(
		AuthenticatedAccount principal,
		UUID classGroupId,
		String name,
		String subject,
		String memo
	) {
		UUID teacherId = setTenantScope(principal);
		ClassGroup classGroup = requireForUpdate(classGroupId, teacherId);
		try {
			classGroup.updateDetails(name, subject, memo, clock.instant());
			classGroups.flush();
			return toView(classGroup, activeStudentCount(classGroupId, teacherId));
		}
		catch (IllegalArgumentException exception) {
			throw invalidRequest(exception);
		}
		catch (IllegalStateException exception) {
			throw invalidState(exception);
		}
	}

	@Transactional
	public ClassView archive(AuthenticatedAccount principal, UUID classGroupId) {
		UUID teacherId = setTenantScope(principal);
		ClassGroup classGroup = requireForUpdate(classGroupId, teacherId);

		// class_groups가 입반과 보관의 공통 직렬화 지점이다. 같은 클래스의
		// 보관 요청은 이 잠금 뒤 순서대로 현재 상태를 다시 확인한다.
		List<com.checkon.roster.domain.ClassEnrollment> activeEnrollments =
			enrollments.findAllForUpdate(
				classGroupId, teacherId, RelationshipStatus.ACTIVE
			);
		Instant now = clock.instant();
		try {
			for (var enrollment : activeEnrollments) {
				enrollment.end(now);
			}
			// DB trigger가 활성 소속이 남은 보관을 거절하므로 클래스 상태를
			// 바꾸기 전에 소속 변경을 명시적으로 flush한다.
			enrollments.flush();
			classGroup.archive(now);
			classGroups.flush();
			return toView(classGroup, 0);
		}
		catch (IllegalArgumentException | IllegalStateException exception) {
			throw invalidState(exception);
		}
	}

	private UUID setTenantScope(AuthenticatedAccount principal) {
		if (principal == null || principal.role() != AccountRole.TEACHER
			|| principal.teacherProfileId() == null) {
			throw ClassManagementException.of(
				ClassManagementException.Reason.INVALID_PRINCIPAL,
				"valid teacher principal is required"
			);
		}
		UUID teacherId = principal.teacherProfileId();
		// set_config(..., true)는 반드시 이 @Transactional 경계 안에서
		// 실행해 커넥션 풀의 다음 요청으로 테넌트 값이 새지 않게 한다.
		tenantContext.setCurrentTeacher(teacherId);
		return teacherId;
	}

	private ClassGroup requireForUpdate(UUID classGroupId, UUID teacherId) {
		if (classGroupId == null) {
			throw notFound();
		}
		// 다른 테넌트와 실제 없음은 같은 조회와 같은 404를 사용한다.
		return classGroups.findByIdAndTeacherIdForUpdate(classGroupId, teacherId)
			.orElseThrow(ClassManagementService::notFound);
	}

	private long activeStudentCount(UUID classGroupId, UUID teacherId) {
		return enrollments.countByClassGroupIdAndTeacherIdAndStatus(
			classGroupId, teacherId, RelationshipStatus.ACTIVE
		);
	}

	private static void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ClassManagementException.of(
				ClassManagementException.Reason.INVALID_REQUEST,
				"invalid page request"
			);
		}
	}

	private static ClassView toView(Row row) {
		return new ClassView(
			row.classId(), row.name(), row.subject(), row.memo(), row.status(),
			row.activeStudentCount(), row.createdAt(), row.updatedAt()
		);
	}

	private static ClassView toView(ClassGroup classGroup, long activeStudentCount) {
		return new ClassView(
			classGroup.id(), classGroup.name(), classGroup.subject(), classGroup.memo(),
			classGroup.status(), activeStudentCount, classGroup.createdAt(),
			classGroup.updatedAt()
		);
	}

	private static ClassManagementException notFound() {
		return ClassManagementException.of(
			ClassManagementException.Reason.NOT_FOUND,
			"class not found"
		);
	}

	private static ClassManagementException invalidRequest(RuntimeException cause) {
		return ClassManagementException.of(
			ClassManagementException.Reason.INVALID_REQUEST,
			cause.getMessage()
		);
	}

	private static ClassManagementException invalidState(RuntimeException cause) {
		return ClassManagementException.of(
			ClassManagementException.Reason.INVALID_STATE,
			cause.getMessage()
		);
	}

	public record ClassPage(
		List<ClassView> content,
		int page,
		int size,
		long totalElements,
		long totalPages
	) {
		public ClassPage {
			content = List.copyOf(content);
		}
	}

	public record ClassView(
		UUID classId,
		String name,
		String subject,
		String memo,
		ClassGroupStatus status,
		long activeStudentCount,
		Instant createdAt,
		Instant updatedAt
	) {
	}
}
