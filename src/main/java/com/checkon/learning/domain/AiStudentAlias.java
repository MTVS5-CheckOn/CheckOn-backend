package com.checkon.learning.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Opaque AI-only identity; it is not the mutable roster display alias. */
@Entity
@Table(name = "ai_student_aliases")
public class AiStudentAlias {
	@Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;
	@Column(name = "teacher_id", nullable = false) private UUID teacherId;
	@Column(name = "student_id", nullable = false) private UUID studentId;
	@Column(nullable = false, length = 40) private String alias;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	protected AiStudentAlias() { }
	public UUID id() { return id; }
	public UUID teacherId() { return teacherId; }
	public UUID studentId() { return studentId; }
	public String alias() { return alias; }
}
