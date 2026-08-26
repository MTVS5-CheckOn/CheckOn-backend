package com.checkon.member.integration.account;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.checkon.account.domain.Account;
import com.checkon.account.domain.AccountPasswordCredential;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.domain.EmailAddress;
import com.checkon.account.infrastructure.persistence.AccountPasswordCredentialRepository;
import com.checkon.account.infrastructure.persistence.AccountRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.security.MemberRole;

/**
 * 학생·학부모 계정을 기존 account 경계의 엔티티·리포지토리로 만든다.
 *
 * <p>member 에서 {@code com.checkon.account} 를 import 하는 곳은 {@code integration/account}
 * 패키지뿐이다(코드 규칙 G2). 계정 생성 규칙을 member 가 다시 구현하면 두 벌이 갈린다 —
 * 이메일 정규화·중복 방어·해시 알고리즘 고정을 전부 기존 것에 맡긴다.</p>
 *
 * <p>방어 순서는 {@code TeacherSignUpService} 와 같다. 사전 조회는 흔한 중복에 읽기 쉬운 오류를
 * 주기 위한 것이고, 최종 방어는 {@code uq_accounts_email_case_insensitive} 다.</p>
 */
@Component
public class AccountWriterAdapter {

	private final AccountRepository accountRepository;
	private final AccountPasswordCredentialRepository credentialRepository;
	private final PasswordEncoder passwordEncoder;

	public AccountWriterAdapter(
		AccountRepository accountRepository,
		AccountPasswordCredentialRepository credentialRepository,
		PasswordEncoder passwordEncoder
	) {
		this.accountRepository = accountRepository;
		this.credentialRepository = credentialRepository;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * 🔴 인자를 {@code MemberRole} 로 받는다. {@code AccountRole} 을 노출하면 호출부인
	 * {@code auth/application} 이 남의 패키지를 import 하게 되고 그건 코드 규칙 G2 위반이다.
	 * 역할 변환은 이 어댑터 한 곳에서만 한다.
	 *
	 * <p>강사 가입은 기존 경로가 소유한다 — {@code MemberRole} 에 TEACHER 가 없으므로
	 * 여기서 다시 방어할 필요가 없다. 타입이 이미 막는다.</p>
	 */
	public MemberAccountCreation create(
		String rawEmail,
		String rawPassword,
		MemberRole role,
		Instant now
	) {
		AccountRole accountRole = role == MemberRole.STUDENT
			? AccountRole.STUDENT
			: AccountRole.PARENT;
		// 🔴 정규화는 여기서만 한다. member 안에서 toLowerCase() 를 직접 부르지 않는다.
		EmailAddress email = EmailAddress.of(rawEmail);
		if (accountRepository.existsByEmail(email.value())) {
			throw new MemberException(
				MemberErrorCode.EMAIL_ALREADY_EXISTS, "email is already registered");
		}

		Account account = Account.register(email, accountRole, now);
		try {
			// flush 시점에 DB 유일 인덱스를 즉시 확인한다. 사전 조회를 동시에 통과해도
			// PostgreSQL 이 한 건만 허용한다.
			accountRepository.saveAndFlush(account);
		}
		catch (DataIntegrityViolationException exception) {
			throw new MemberException(
				MemberErrorCode.EMAIL_ALREADY_EXISTS, "email is already registered");
		}

		// 원문 비밀번호는 이 메서드 밖으로 나가지 않는다.
		String passwordHash = passwordEncoder.encode(rawPassword);
		// 알고리즘은 엔티티가 BCRYPT 로 고정한다. CHECK 도 'BCRYPT' 하나뿐이라 값을 만들지 않는다.
		credentialRepository.save(AccountPasswordCredential.bcrypt(account, passwordHash, now));

		return new MemberAccountCreation(account.id(), email.value());
	}
}
