package com.checkon.learning.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.domain.LearningRecord;
import com.checkon.learning.infrastructure.persistence.LearningRecordRepository;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.infrastructure.persistence.ClassEnrollmentRepository;
import com.checkon.roster.infrastructure.persistence.ClassGroupRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

@Service
public class RegisterLearningRecordService {
	private final LearningRecordRepository records;
	private final TeacherStudentRelationshipRepository relationships;
	private final ClassGroupRepository classes;
	private final ClassEnrollmentRepository enrollments;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;

	public RegisterLearningRecordService(
		LearningRecordRepository records,
		TeacherStudentRelationshipRepository relationships,
		ClassGroupRepository classes,
		ClassEnrollmentRepository enrollments,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock
	) {
		this.records = records;
		this.relationships = relationships;
		this.classes = classes;
		this.enrollments = enrollments;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	@Transactional
	public UUID register(UUID authenticatedTeacherId, RegisterLearningRecordCommand command) {
		return registerRecord(authenticatedTeacherId, command).id();
	}

	@Transactional
	LearningRecord registerRecord(
		UUID authenticatedTeacherId,
		RegisterLearningRecordCommand command
	) {
		if (authenticatedTeacherId == null) {
			throw LearningRecordRegistrationException.invalidTeacherPrincipal();
		}
		Objects.requireNonNull(command, "command must not be null");
		// set_config(..., true)는 트랜잭션 로컬 값이다. 반드시 @Transactional이
		// 시작한 뒤 설정해야 커밋/롤백 때 풀 커넥션에서 자동으로 제거된다.
		tenantContext.setCurrentTeacher(authenticatedTeacherId);
		if (!relationships.existsByTeacherIdAndStudentIdAndStatus(
			authenticatedTeacherId,
			command.studentId(),
			RelationshipStatus.ACTIVE
		)) {
			// 다른 강사의 학생과 비활성 관계를 같은 결과로 처리해 학생 존재 여부를
			// 테넌트 밖에 노출하지 않는다.
			throw LearningRecordRegistrationException.inaccessibleStudent();
		}
		if (command.classGroupId() != null) {
			if (classes.findByIdAndTeacherId(
				command.classGroupId(), authenticatedTeacherId
			).isEmpty()) {
				// 반도 다른 강사의 소유인지 존재하지 않는지 구분하지 않는다.
				throw LearningRecordRegistrationException.inaccessibleClassGroup();
			}
			if (!enrollments.existsAt(
				command.classGroupId(),
				authenticatedTeacherId,
				command.studentId(),
				command.occurredAt()
			)) {
				// 소속 이력의 존재 여부 역시 동일한 404 응답으로 감춰 다른 학생의
				// 반 이동 이력이 테넌트 밖으로 드러나지 않게 한다.
				throw LearningRecordRegistrationException.inaccessibleClassEnrollment();
			}
		}
		try {
			LearningRecord record = LearningRecord.create(new LearningRecord.Draft(
				authenticatedTeacherId,
				command.studentId(),
				command.classGroupId(),
				command.recordType(),
				command.occurredAt(),
				command.sourceType(),
				command.externalRecordRef(),
				command.correct(),
				command.durationSec(),
				command.passageWordCount(),
				command.areaTag(),
				command.subjectTrack(),
				command.typeTag(),
				command.itemFormat(),
				command.assignmentTitleText()
			), Instant.now(clock));
			return records.save(record);
		}
		catch (IllegalArgumentException exception) {
			throw LearningRecordRegistrationException.invalidRecord(exception);
		}
	}
}
