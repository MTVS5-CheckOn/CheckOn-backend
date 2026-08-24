package com.checkon.roster.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.checkon.account.domain.Account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * 학생의 Roster 업무 식별자와 운영 정보를 소유한다.
 *
 * <p>학생 가입 및 학부모 연결 방식이 아직 확정되지 않았으므로 Account 연결은
 * 선택 사항이다. 로그인 정보는 이 엔티티가 아니라 Account가 계속 소유한다.</p>
 */
@Entity
@Table(name = "student_profiles")
public class StudentProfile {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "account_id", unique = true)
	private Account account;

	@Column(nullable = false, length = 80)
	private String alias;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	private Integer grade;

	@Column(name = "account_linked_at")
	private Instant accountLinkedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected StudentProfile() {
	}

	private StudentProfile(String alias, Integer grade, Instant createdAt) {
		this.alias = requireText(alias, "alias", 80);
		this.grade = validateGrade(grade);
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		this.updatedAt = createdAt;
	}

	public static StudentProfile create(
		String alias,
		Integer grade,
		Instant createdAt
	) {
		return new StudentProfile(alias, grade, createdAt);
	}

	private static Integer validateGrade(Integer grade) {
		if (grade != null && (grade < 1 || grade > 3)) {
			throw new IllegalArgumentException("grade must be between 1 and 3");
		}
		return grade;
	}

	private static String requireText(String value, String field, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		String normalized = value.trim();
		if (normalized.length() > maxLength) {
			throw new IllegalArgumentException(
				field + " must not exceed " + maxLength + " characters"
			);
		}
		return normalized;
	}

	public UUID id() {
		return id;
	}

	public UUID accountId() {
		return account == null ? null : account.id();
	}

	public String alias() {
		return alias;
	}

	public Integer grade() {
		return grade;
	}
}
