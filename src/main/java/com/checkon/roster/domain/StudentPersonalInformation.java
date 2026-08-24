package com.checkon.roster.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.checkon.account.domain.AccountRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "student_personal_information")
public class StudentPersonalInformation {

	@Id
	@Column(name = "student_id")
	private UUID studentId;

	@Column(name = "real_name", nullable = false, length = 100)
	private String realName;

	@Column(name = "updated_by_account_id", nullable = false)
	private UUID updatedByAccountId;

	@Column(name = "updated_by_role", nullable = false, length = 20)
	private String updatedByRole;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected StudentPersonalInformation() {
	}

	private StudentPersonalInformation(
		UUID studentId,
		String realName,
		UUID updatedByAccountId,
		AccountRole updatedByRole,
		Instant now
	) {
		this.studentId = Objects.requireNonNull(studentId, "studentId must not be null");
		changeRealName(realName, updatedByAccountId, updatedByRole, now);
		this.createdAt = this.updatedAt;
	}

	public static StudentPersonalInformation create(
		UUID studentId,
		String realName,
		UUID updatedByAccountId,
		AccountRole updatedByRole,
		Instant now
	) {
		return new StudentPersonalInformation(
			studentId, realName, updatedByAccountId, updatedByRole, now
		);
	}

	public void changeRealName(
		String realName,
		UUID updatedByAccountId,
		AccountRole updatedByRole,
		Instant now
	) {
		this.realName = validateRealName(realName);
		this.updatedByAccountId = Objects.requireNonNull(
			updatedByAccountId, "updatedByAccountId must not be null"
		);
		if (updatedByRole != AccountRole.TEACHER) {
			throw new IllegalArgumentException("updatedByRole must be TEACHER");
		}
		this.updatedByRole = updatedByRole.name();
		this.updatedAt = Objects.requireNonNull(now, "now must not be null");
	}

	private static String validateRealName(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("realName must not be blank");
		}
		if (!value.equals(value.trim())) {
			throw new IllegalArgumentException("realName must not have surrounding whitespace");
		}
		if (value.length() > 100) {
			throw new IllegalArgumentException("realName must not exceed 100 characters");
		}
		return value;
	}

	public UUID studentId() {
		return studentId;
	}

	public String realName() {
		return realName;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}

