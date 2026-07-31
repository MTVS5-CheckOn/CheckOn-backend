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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClassGroupStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ClassGroup() {
	}

	private ClassGroup(UUID teacherId, String name, Instant createdAt) {
		this.teacherId = Objects.requireNonNull(teacherId, "teacherId must not be null");
		this.name = normalizeName(name);
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		this.updatedAt = createdAt;
		this.status = ClassGroupStatus.ACTIVE;
	}

	public static ClassGroup create(UUID teacherId, String name, Instant createdAt) {
		return new ClassGroup(teacherId, name, createdAt);
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

	public UUID id() {
		return id;
	}

	public UUID teacherId() {
		return teacherId;
	}
}

