package com.checkon.account.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 한 기기의 Refresh Token 수명과 회전 이력을 소유하는 인증 세션이다.
 *
 * <p>원문 Refresh Token은 탈취 시 즉시 자격 증명이 되므로 저장하지 않는다.
 * 충분히 무작위인 토큰의 SHA-256 해시만 보관해 검증과 유일 조회를 가능하게 한다.</p>
 */
@Entity
@Table(name = "authentication_sessions")
public class AuthenticationSession {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@Column(name = "refresh_token_hash", nullable = false, length = 71, unique = true)
	private String refreshTokenHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AuthenticationSessionStatus status;

	@Column(name = "issued_at", nullable = false)
	private Instant issuedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	@Column(name = "rotation_count", nullable = false)
	private int rotationCount;

	protected AuthenticationSession() {
	}

	private AuthenticationSession(
		Account account,
		String refreshTokenHash,
		Instant issuedAt,
		Instant expiresAt
	) {
		this.account = Objects.requireNonNull(account, "account must not be null");
		this.refreshTokenHash = requireTokenHash(refreshTokenHash);
		this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
		this.expiresAt = requireAfter(expiresAt, issuedAt, "expiresAt");
		this.status = AuthenticationSessionStatus.ACTIVE;
	}

	public static AuthenticationSession issue(
		Account account,
		String refreshTokenHash,
		Instant issuedAt,
		Instant expiresAt
	) {
		return new AuthenticationSession(
			account,
			refreshTokenHash,
			issuedAt,
			expiresAt
		);
	}

	/**
	 * 잠긴 세션 행에서 현재 토큰을 새 해시로 교체한다.
	 *
	 * <p>검증과 변경을 같은 트랜잭션에서 수행해야 두 동시 갱신 중 하나만
	 * 성공하고, 먼저 사용된 토큰이 즉시 무효가 된다.</p>
	 */
	public void rotate(String newRefreshTokenHash, Instant now) {
		requireUsable(now);
		this.refreshTokenHash = requireTokenHash(newRefreshTokenHash);
		this.lastUsedAt = now;
		this.rotationCount++;
	}

	public void revoke(Instant now) {
		if (status == AuthenticationSessionStatus.REVOKED) {
			return;
		}
		if (now.isBefore(issuedAt)) {
			throw new IllegalArgumentException("revokedAt must not precede issuedAt");
		}
		this.status = AuthenticationSessionStatus.REVOKED;
		this.revokedAt = now;
	}

	public void requireUsable(Instant now) {
		Objects.requireNonNull(now, "now must not be null");
		if (status != AuthenticationSessionStatus.ACTIVE) {
			throw new IllegalStateException("session is not active");
		}
		if (!now.isBefore(expiresAt)) {
			this.status = AuthenticationSessionStatus.EXPIRED;
			throw new IllegalStateException("session is expired");
		}
	}

	private static String requireTokenHash(String value) {
		if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
			throw new IllegalArgumentException("refreshTokenHash has invalid format");
		}
		return value;
	}

	private static Instant requireAfter(
		Instant value,
		Instant baseline,
		String field
	) {
		Objects.requireNonNull(value, field + " must not be null");
		if (!value.isAfter(baseline)) {
			throw new IllegalArgumentException(field + " must be after issuedAt");
		}
		return value;
	}

	public UUID id() {
		return id;
	}

	public UUID accountId() {
		return account.id();
	}

	public Account account() {
		return account;
	}

	public String refreshTokenHash() {
		return refreshTokenHash;
	}

	public AuthenticationSessionStatus status() {
		return status;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public int rotationCount() {
		return rotationCount;
	}
}
