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
import com.checkon.roster.infrastructure.persistence.ClassGroupRepository;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

@Service
public class SaveLearningRecordService {
	private final LearningRecordRepository records;
	private final TeacherStudentRelationshipRepository relationships;
	private final ClassGroupRepository classes;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;
	public SaveLearningRecordService(LearningRecordRepository records,
		TeacherStudentRelationshipRepository relationships, ClassGroupRepository classes,
		TeacherTenantDatabaseContext tenantContext, Clock clock) {
		this.records = records; this.relationships = relationships; this.classes = classes;
		this.tenantContext = tenantContext; this.clock = clock;
	}

	@Transactional
	public LearningRecord save(UUID authenticatedTeacherId, LearningRecord.Draft draft) {
		Objects.requireNonNull(authenticatedTeacherId, "authenticatedTeacherId must not be null");
		Objects.requireNonNull(draft, "draft must not be null");
		if (!authenticatedTeacherId.equals(draft.teacherId()))
			throw new IllegalArgumentException("teacherId must match authenticated teacher");
		// Tenant context must be set inside this transaction so pooled connections
		// clear it automatically on commit or rollback.
		tenantContext.setCurrentTeacher(authenticatedTeacherId);
		if (!relationships.existsByTeacherIdAndStudentIdAndStatus(
			authenticatedTeacherId, draft.studentId(), RelationshipStatus.ACTIVE))
			throw new IllegalArgumentException("student is not active for this teacher");
		if (draft.classGroupId() != null &&
			classes.findByIdAndTeacherId(draft.classGroupId(), authenticatedTeacherId).isEmpty())
			throw new IllegalArgumentException("class does not belong to this teacher");
		return records.save(LearningRecord.create(draft, Instant.now(clock)));
	}
}
