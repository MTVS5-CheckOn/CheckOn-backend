package com.checkon.account.domain;

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
 * 인증에 필요한 계정 식별자, 역할, 상태를 소유하는 Account 컨텍스트의 엔티티다.
 *
 * <p>표시 이름이나 반 소속 같은 역할별 업무 정보는 이 엔티티에 넣지 않고
 * Roster 컨텍스트의 프로필과 관계 엔티티가 담당한다.</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

	// 프로젝트 식별자 정책에 따라 Hibernate가 영속화 시 UUIDv7을 생성한다.
	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(nullable = false, length = 320)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "withdrawn_at")
	private Instant withdrawnAt;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	protected Account() {
	}

	private Account(EmailAddress email, AccountRole role, Instant createdAt) {
		this.email = Objects.requireNonNull(email, "email must not be null").value();
		this.role = Objects.requireNonNull(role, "role must not be null");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		// 신규 가입 계정은 항상 ACTIVE로 시작하며 역할은 일반 API로 변경하지 않는다.
		this.status = AccountStatus.ACTIVE;
	}

	public static Account register(
		EmailAddress email,
		AccountRole role,
		Instant createdAt
	) {
		return new Account(email, role, createdAt);
	}

	public UUID id() {
		return id;
	}

	public String email() {
		return email;
	}

	public AccountRole role() {
		return role;
	}

	public AccountStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public boolean isActive() {
		return status == AccountStatus.ACTIVE;
	}

	public void recordSuccessfulLogin(Instant loggedInAt) {
		if (!isActive()) {
			throw new IllegalStateException("inactive account cannot log in");
		}
		this.lastLoginAt = Objects.requireNonNull(
			loggedInAt,
			"loggedInAt must not be null"
		);
	}
}
