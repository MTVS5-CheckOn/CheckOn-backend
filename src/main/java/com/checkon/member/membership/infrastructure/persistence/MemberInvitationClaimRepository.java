package com.checkon.member.membership.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code member_invitation_claims} 기록.
 *
 * <p>정책은 {@code member_invitation_claims_member_self_insert/select}(V38) 이며
 * {@code current_checkon_account_id()} 를 요구한다 — 내 claim 만 보이고 내 claim 만 넣는다.</p>
 */
@Repository
public class MemberInvitationClaimRepository {

	/**
	 * 🔴 <b>제약 이름을 SQL 에 적어</b> 그 하나만 무시한다.
	 *
	 * <p>왜 예외를 잡지 않나 — PostgreSQL 은 제약 위반이 나는 순간 트랜잭션을 abort 상태로 만들고
	 * 그 뒤의 모든 문장을 거절한다. 「23505 를 catch 해서 계속 진행」은 <b>같은 트랜잭션에서
	 * 성립하지 않는다</b>(savepoint 없이는). {@code ON CONFLICT ON CONSTRAINT} 는 예외 자체를
	 * 만들지 않으므로 트랜잭션이 살아 있고, <b>이름을 명시했으므로 다른 제약 위반은 그대로 터진다</b> —
	 * 뭉개기가 아니라 이름 기반 분기다(코드 규칙 §5).</p>
	 */
	private static final String INSERT = """
		INSERT INTO member_invitation_claims (id, invitation_id, account_id, claimed_at)
		VALUES (?, ?, ?, ?)
		ON CONFLICT ON CONSTRAINT uq_member_invitation_claims_pair DO NOTHING
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberInvitationClaimRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** @return 새 claim 을 남겼으면 {@code true}, 내가 이미 쓴 코드라 무시됐으면 {@code false} */
	public boolean insertIfAbsent(UUID invitationId, UUID accountId, Instant now) {
		return jdbcTemplate.update(INSERT, UUID.randomUUID(), invitationId, accountId,
			OffsetDateTime.ofInstant(now, ZoneOffset.UTC)) == 1;
	}
}
