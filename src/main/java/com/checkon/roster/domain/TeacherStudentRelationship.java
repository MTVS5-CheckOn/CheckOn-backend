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
 * 강사가 학생을 관리할 수 있었던 기간을 보존하는 관계 이력이다.
 *
 * <p>종료된 행은 재활성화하지 않는다. 같은 학생이 다시 연결되면 새 행을
 * 생성해야 과거 접근권한과 현재 접근권한을 명확히 구분할 수 있다.</p>
 */
@Entity
@Table(name = "teacher_student_relationships")
public class TeacherStudentRelationship {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "teacher_id", nullable = false)
	private UUID teacherId;

	@Column(name = "student_id", nullable = false)
	private UUID studentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RelationshipStatus status;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected TeacherStudentRelationship() {
	}

	private TeacherStudentRelationship(
		UUID teacherId,
		UUID studentId,
		Instant startedAt,
		Instant createdAt
	) {
		this.teacherId = Objects.requireNonNull(teacherId, "teacherId must not be null");
		this.studentId = Objects.requireNonNull(studentId, "studentId must not be null");
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		this.status = RelationshipStatus.ACTIVE;
	}

	public static TeacherStudentRelationship start(
		UUID teacherId,
		UUID studentId,
		Instant startedAt,
		Instant createdAt
	) {
		return new TeacherStudentRelationship(
			teacherId,
			studentId,
			startedAt,
			createdAt
		);
	}

	public void end(Instant endedAt) {
		if (status == RelationshipStatus.ENDED) {
			throw new IllegalStateException("ended relationship cannot transition again");
		}
		Instant endTime = Objects.requireNonNull(endedAt, "endedAt must not be null");
		if (endTime.isBefore(startedAt)) {
			throw new IllegalArgumentException("endedAt must not be before startedAt");
		}
		this.endedAt = endTime;
		this.status = RelationshipStatus.ENDED;
	}

	public UUID id() {
		return id;
	}

	public UUID studentId() {
		return studentId;
	}

	public RelationshipStatus status() {
		return status;
	}

	public Instant endedAt() {
		return endedAt;
	}
}

