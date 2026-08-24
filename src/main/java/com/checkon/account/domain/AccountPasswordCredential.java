package com.checkon.account.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Account의 로컬 비밀번호 자격정보를 별도로 보관하는 엔티티다.
 *
 * <p>원문 비밀번호는 이 객체에 전달하지 않는다. application 계층에서 해시한 결과와
 * 알고리즘 정보만 저장해 자격정보 접근 범위를 Account 자체보다 좁게 유지한다.</p>
 */
@Entity
@Table(name = "account_password_credentials")
public class AccountPasswordCredential {

	@Id
	@Column(name = "account_id")
	private UUID accountId;

	// 공유 PK 매핑으로 한 Account에 비밀번호 자격정보가 하나만 존재하도록 한다.
	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "password_algorithm", nullable = false, length = 30)
	private PasswordAlgorithm passwordAlgorithm;

	@Column(name = "password_changed_at", nullable = false)
	private Instant passwordChangedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AccountPasswordCredential() {
	}

	private AccountPasswordCredential(
		Account account,
		String passwordHash,
		Instant createdAt
	) {
		this.account = Objects.requireNonNull(account, "account must not be null");
		this.passwordHash = requireText(passwordHash, "passwordHash");
		this.passwordAlgorithm = PasswordAlgorithm.BCRYPT;
		this.passwordChangedAt =
			Objects.requireNonNull(createdAt, "createdAt must not be null");
		this.createdAt = createdAt;
	}

	public static AccountPasswordCredential bcrypt(
		Account account,
		String passwordHash,
		Instant createdAt
	) {
		return new AccountPasswordCredential(account, passwordHash, createdAt);
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}

	public UUID accountId() {
		return accountId;
	}

	public String passwordHash() {
		return passwordHash;
	}

	public PasswordAlgorithm passwordAlgorithm() {
		return passwordAlgorithm;
	}
}
