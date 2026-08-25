package com.checkon.member.auth.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.infrastructure.persistence.MemberDisplayNameRepository;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.integration.account.AccountWriterAdapter;
import com.checkon.member.integration.account.MemberAccountCreation;
import com.checkon.member.common.security.MemberRole;
import com.checkon.member.integration.roster.RosterProfileWriterAdapter;

/**
 * 학부모 가입.
 *
 * <p>🔴 {@code parent_profiles} 는 ENABLE + FORCE ROW LEVEL SECURITY 이고 INSERT 정책이
 * {@code account_id = current_checkon_account_id()} 를 요구한다. 그래서 순서가 계약이다:</p>
 *
 * <pre>
 * 1. accounts flush        ← accountId 확정
 * 2. setCurrentAccount     ← 트랜잭션 로컬 주체 설정
 * 3. parent_profiles INSERT
 * </pre>
 *
 * <p>🔴 2번을 3번 뒤로 옮기면 INSERT 가 RLS 로 거절된다. JPA save 와 JdbcTemplate INSERT 가
 * 섞이므로 Hibernate 자동 flush 에 기대지 않는다 — 1번이 {@code saveAndFlush} 다.</p>
 */
@Service
public class ParentSignUpService {

	private final AccountWriterAdapter accountWriter;
	private final RosterProfileWriterAdapter rosterWriter;
	private final MemberDisplayNameRepository displayNameRepository;
	private final MemberDatabaseContext databaseContext;
	private final Clock clock;

	public ParentSignUpService(
		AccountWriterAdapter accountWriter,
		RosterProfileWriterAdapter rosterWriter,
		MemberDisplayNameRepository displayNameRepository,
		MemberDatabaseContext databaseContext,
		Clock clock
	) {
		this.accountWriter = accountWriter;
		this.rosterWriter = rosterWriter;
		this.displayNameRepository = displayNameRepository;
		this.databaseContext = databaseContext;
		this.clock = clock;
	}

	@Transactional
	public MemberSignUpResult signUp(MemberSignUpCommand command) {
		Instant now = Instant.now(clock);
		String name = command.name().strip();

		MemberAccountCreation account =
			accountWriter.create(command.email(), command.password(), MemberRole.PARENT, now);

		databaseContext.setCurrentAccount(account.accountId());

		rosterWriter.insertParentProfile(account.accountId(), now);
		displayNameRepository.insert(account.accountId(), name, now);

		// 🔴 학부모는 공개 학생 ID 도 활성화 상태도 없다. null 을 그대로 둔다.
		return new MemberSignUpResult(account.accountId(), MemberRole.PARENT.name(), null, null);
	}
}
