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
 * 한 강사가 소유하는 수업 반이다.
 *
 * <p>TeacherProfile은 다른 Aggregate이므로 객체 그래프 대신 식별자만 저장해
 * 반의 생명주기가 강사 프로필 영속화에 결합되지 않도록 한다.</p>
 */
@Entity
@Table(name = "class_groups")
public class ClassGroup {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "teacher_id", nullable = false)
	private UUID teacherId;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 100)
	private String subject;

	@Column(length = 1000)
	private String memo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClassGroupStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ClassGroup() {
	}

	private ClassGroup(
		UUID teacherId,
		String name,
		String subject,
		String memo,
		Instant createdAt
	) {
		this.teacherId = Objects.requireNonNull(teacherId, "teacherId must not be null");
		this.name = normalizeName(name);
		this.subject = normalizeSubject(subject);
		this.memo = validateMemo(memo);
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		this.updatedAt = createdAt;
		this.status = ClassGroupStatus.ACTIVE;
	}

	public static ClassGroup create(
		UUID teacherId,
		String name,
		String subject,
		String memo,
		Instant createdAt
	) {
		return new ClassGroup(teacherId, name, subject, memo, createdAt);
	}

	public void updateDetails(
		String name,
		String subject,
		String memo,
		Instant updatedAt
	) {
		if (status != ClassGroupStatus.ACTIVE) {
			throw new IllegalStateException("archived class cannot be updated");
		}
		Instant updateTime = requireNotBeforeCreatedAt(updatedAt, "updatedAt");
		String normalizedName = normalizeName(name);
		String normalizedSubject = normalizeSubject(subject);
		String validatedMemo = validateMemo(memo);
		this.name = normalizedName;
		this.subject = normalizedSubject;
		this.memo = validatedMemo;
		this.updatedAt = updateTime;
	}

	/**
	 * 보관 재요청은 기존 시각을 바꾸지 않는다.
	 *
	 * @return 이번 호출에서 ACTIVE에서 ARCHIVED로 전이했으면 {@code true}
	 */
	public boolean archive(Instant archivedAt) {
		if (status == ClassGroupStatus.ARCHIVED) {
			return false;
		}
		Instant archiveTime = requireNotBeforeCreatedAt(archivedAt, "archivedAt");
		this.status = ClassGroupStatus.ARCHIVED;
		this.updatedAt = archiveTime;
		return true;
	}

	private static String normalizeName(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		String normalized = value.trim();
		if (normalized.length() > 100) {
			throw new IllegalArgumentException("name must not exceed 100 characters");
		}
		return normalized;
	}

	private static String normalizeSubject(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("subject must not be blank");
		}
		String normalized = value.trim();
		if (normalized.length() > 100) {
			throw new IllegalArgumentException("subject must not exceed 100 characters");
		}
		return normalized;
	}

	private static String validateMemo(String value) {
		if (value != null && value.length() > 1000) {
			throw new IllegalArgumentException("memo must not exceed 1000 characters");
		}
		return value;
	}

	private Instant requireNotBeforeCreatedAt(Instant value, String field) {
		Instant time = Objects.requireNonNull(value, field + " must not be null");
		if (time.isBefore(createdAt)) {
			throw new IllegalArgumentException(field + " must not be before createdAt");
		}
		return time;
	}

	public UUID id() {
		return id;
	}

	public UUID teacherId() {
		return teacherId;
	}

	public String name() {
		return name;
	}

	public String subject() {
		return subject;
	}

	public String memo() {
		return memo;
	}

	public ClassGroupStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}

