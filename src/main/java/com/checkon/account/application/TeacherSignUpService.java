package com.checkon.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.account.domain.Account;
import com.checkon.account.domain.AccountPasswordCredential;
import com.checkon.account.domain.AccountRole;
import com.checkon.account.domain.EmailAddress;
import com.checkon.account.infrastructure.persistence.AccountPasswordCredentialRepository;
import com.checkon.account.infrastructure.persistence.AccountRepository;
import com.checkon.roster.domain.TeacherProfile;
import com.checkon.roster.infrastructure.persistence.TeacherProfileRepository;

/**
 * 강사 Account, 비밀번호 자격정보, TeacherProfile을 하나의 가입 단위로 생성한다.
 */
@Service
public class TeacherSignUpService {

	private static final int MINIMUM_PASSWORD_LENGTH = 8;

	private final AccountRepository accountRepository;
	private final AccountPasswordCredentialRepository credentialRepository;
	private final TeacherProfileRepository teacherProfileRepository;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public TeacherSignUpService(
		AccountRepository accountRepository,
		AccountPasswordCredentialRepository credentialRepository,
		TeacherProfileRepository teacherProfileRepository,
		PasswordEncoder passwordEncoder,
		Clock clock
	) {
		this.accountRepository = accountRepository;
		this.credentialRepository = credentialRepository;
		this.teacherProfileRepository = teacherProfileRepository;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	@Transactional
	public TeacherSignUpResult signUp(
		String rawEmail,
		String rawPassword,
		String displayName
	) {
		EmailAddress email = EmailAddress.of(rawEmail);
		validatePassword(rawPassword);
		// 먼저 조회해 일반적인 중복 요청에는 이해하기 쉬운 업무 예외를 반환한다.
		if (accountRepository.existsByEmail(email.value())) {
			throw new DuplicateEmailException();
		}

		Instant now = Instant.now(clock);
		Account account = Account.register(email, AccountRole.TEACHER, now);
		try {
			// flush 시점에 DB 유일 인덱스를 즉시 확인한다. 두 가입 요청이 사전 조회를
			// 동시에 통과해도 PostgreSQL이 한 건만 허용한다.
			accountRepository.saveAndFlush(account);
		}
		catch (DataIntegrityViolationException exception) {
			throw new DuplicateEmailException();
		}

		// 원문 비밀번호는 이 메서드 밖으로 전달하거나 엔티티에 저장하지 않는다.
		String passwordHash = passwordEncoder.encode(rawPassword);
		credentialRepository.save(
			AccountPasswordCredential.bcrypt(account, passwordHash, now)
		);
		TeacherProfile teacherProfile = teacherProfileRepository.save(
			TeacherProfile.create(account, displayName, now)
		);

		return new TeacherSignUpResult(
			account.id(),
			teacherProfile.id(),
			account.role(),
			account.email()
		);
	}

	private static void validatePassword(String rawPassword) {
		if (rawPassword == null || rawPassword.length() < MINIMUM_PASSWORD_LENGTH) {
			throw new IllegalArgumentException(
				"password must contain at least 8 characters"
			);
		}
	}

	public record TeacherSignUpResult(
		UUID accountId,
		UUID teacherId,
		AccountRole role,
		String email
	) {
	}
}
