package com.checkon.roster.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.checkon.account.domain.Account;
import com.checkon.account.domain.AccountRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * 강사의 서비스 표시 정보를 소유하는 Roster 컨텍스트 엔티티다.
 *
 * <p>로그인 이메일과 비밀번호는 Account가 소유하고, 강사 업무 화면에서 사용하는
 * 정보만 이 프로필에 둔다.</p>
 */
@Entity
@Table(name = "teacher_profiles")
public class TeacherProfile {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, unique = true)
	private Account account;

	@Column(name = "display_name", nullable = false, length = 80)
	private String displayName;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TeacherProfile() {
	}

	private TeacherProfile(
		Account account,
		String displayName,
		Instant createdAt
	) {
		this.account = requireTeacherAccount(account);
		this.displayName = normalizeDisplayName(displayName);
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		this.updatedAt = createdAt;
	}

	public static TeacherProfile create(
		Account account,
		String displayName,
		Instant createdAt
	) {
		return new TeacherProfile(account, displayName, createdAt);
	}

	private static Account requireTeacherAccount(Account account) {
		Objects.requireNonNull(account, "account must not be null");
		// DB의 일반 FK만으로는 연결된 Account 역할까지 검사할 수 없으므로
		// 프로필 생성 시 도메인 규칙으로 TEACHER 역할을 강제한다.
		if (account.role() != AccountRole.TEACHER) {
			throw new IllegalArgumentException("account role must be TEACHER");
		}
		return account;
	}

	private static String normalizeDisplayName(String value) {
		if (value == null) {
			throw new IllegalArgumentException("displayName must not be null");
		}
		String normalized = value.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("displayName must not be blank");
		}
		if (normalized.length() > 80) {
			throw new IllegalArgumentException(
				"displayName must not exceed 80 characters"
			);
		}
		return normalized;
	}

	public UUID id() {
		return id;
	}

	public UUID accountId() {
		return account.id();
	}

	public String displayName() {
		return displayName;
	}
}
