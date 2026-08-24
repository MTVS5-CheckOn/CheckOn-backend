package com.checkon.roster.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 학생이 반에 소속되었던 기간을 보존한다.
 *
 * <p>teacherId는 반 소유자와 활성 강사 관계가 같은지 DB가 검증하기 위한
 * 스칼라 참조다. 다른 Aggregate를 JPA 객체 그래프로 연결하지 않는다.</p>
 */
@Entity
@Table(name = "class_enrollments")
public class ClassEnrollment {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "class_group_id", nullable = false)
	private UUID classGroupId;

	@Column(name = "teacher_id", nullable = false)
	private UUID teacherId;

	@Column(name = "student_id", nullable = false)
	private UUID studentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RelationshipStatus status;

	@Column(name = "enrolled_at", nullable = false)
	private Instant enrolledAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ClassEnrollment() {
	}

	private ClassEnrollment(
		UUID classGroupId,
		UUID teacherId,
		UUID studentId,
		Instant enrolledAt,
		Instant createdAt
	) {
		this.classGroupId = Objects.requireNonNull(
			classGroupId,
			"classGroupId must not be null"
		);
		this.teacherId = Objects.requireNonNull(teacherId, "teacherId must not be null");
		this.studentId = Objects.requireNonNull(studentId, "studentId must not be null");
		this.enrolledAt = Objects.requireNonNull(
			enrolledAt,
			"enrolledAt must not be null"
		);
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		this.status = RelationshipStatus.ACTIVE;
	}

	public static ClassEnrollment enroll(
		UUID classGroupId,
		UUID teacherId,
		UUID studentId,
		Instant enrolledAt,
		Instant createdAt
	) {
		return new ClassEnrollment(
			classGroupId,
			teacherId,
			studentId,
			enrolledAt,
			createdAt
		);
	}

	public void end(Instant endedAt) {
		if (status == RelationshipStatus.ENDED) {
			throw new IllegalStateException("ended enrollment cannot transition again");
		}
		Instant endTime = Objects.requireNonNull(endedAt, "endedAt must not be null");
		if (endTime.isBefore(enrolledAt)) {
			throw new IllegalArgumentException("endedAt must not be before enrolledAt");
		}
		this.endedAt = endTime;
		this.status = RelationshipStatus.ENDED;
	}

	public void pause() {
		if (status != RelationshipStatus.ACTIVE) {
			throw new IllegalStateException("only active enrollment can be paused");
		}
		this.status = RelationshipStatus.PAUSED;
	}

	public void resume() {
		if (status != RelationshipStatus.PAUSED) {
			throw new IllegalStateException("only paused enrollment can be resumed");
		}
		this.status = RelationshipStatus.ACTIVE;
	}

	public UUID id() {
		return id;
	}

	public UUID classGroupId() { return classGroupId; }
	public UUID teacherId() { return teacherId; }
	public UUID studentId() { return studentId; }
	public Instant enrolledAt() { return enrolledAt; }

	public RelationshipStatus status() {
		return status;
	}
}
