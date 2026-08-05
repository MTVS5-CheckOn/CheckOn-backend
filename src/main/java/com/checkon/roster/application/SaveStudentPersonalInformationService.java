package com.checkon.roster.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.domain.StudentPersonalInformation;
import com.checkon.roster.infrastructure.persistence.StudentPersonalInformationRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

@Service
public class SaveStudentPersonalInformationService {
	private final StudentPersonalInformationRepository personalInformation;
	private final TeacherStudentRelationshipRepository relationships;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public SaveStudentPersonalInformationService(
		StudentPersonalInformationRepository personalInformation,
		TeacherStudentRelationshipRepository relationships,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.personalInformation = personalInformation;
		this.relationships = relationships;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional
	public Result save(
		AuthenticatedAccount principal,
		UUID studentId,
		String realName
	) {
		if (principal == null || principal.accountId() == null
			|| principal.teacherProfileId() == null || principal.role() != AccountRole.TEACHER) {
			throw StudentPersonalInformationException.of(
				StudentPersonalInformationException.Reason.INVALID_PRINCIPAL,
				"valid teacher principal is required"
			);
		}
		if (studentId == null) {
			throw StudentPersonalInformationException.of(
				StudentPersonalInformationException.Reason.NOT_FOUND,
				"student is inaccessible"
			);
		}

		tenantContext.setCurrentTeacher(principal.teacherProfileId());
		if (!relationships.existsByTeacherIdAndStudentIdAndStatus(
			principal.teacherProfileId(), studentId, RelationshipStatus.ACTIVE
		)) {
			throw StudentPersonalInformationException.of(
				StudentPersonalInformationException.Reason.NOT_FOUND,
				"student is inaccessible"
			);
		}

		Instant now = clock.instant();
		try {
			var existing = personalInformation.findById(studentId);
			StudentPersonalInformation information = existing.orElseGet(
				() -> StudentPersonalInformation.create(
					studentId, realName, principal.accountId(), principal.role(), now
				)
			);
			if (existing.isPresent()) {
				information.changeRealName(
					realName, principal.accountId(), principal.role(), now
				);
			}
			StudentPersonalInformation saved = personalInformation.saveAndFlush(information);
			return new Result(saved.studentId(), saved.realName(), saved.updatedAt());
		}
		catch (IllegalArgumentException exception) {
			throw StudentPersonalInformationException.of(
				StudentPersonalInformationException.Reason.INVALID_NAME,
				"invalid real name"
			);
		}
	}

	public record Result(UUID studentId, String studentName, Instant updatedAt) {
	}
}
