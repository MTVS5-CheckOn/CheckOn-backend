package com.checkon.member.membership.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.membership.domain.InvitationTargetRole;
import com.checkon.member.membership.domain.MemberInvitationCode;

/**
 * {@code member_invitation_codes} 조회.
 *
 * <p>🔴 이 테이블은 RLS 밖이다(MB-30) — 코드 소유자는 강사인데 조회하는 것은 학생·학부모라
 * 소유자 기준 정책이 성립하지 않는다. 방어는 <b>평문 미저장</b>과 해시 동등 비교, 그리고
 * 호출부의 레이트 리밋이다. 🔴 {@code teacher_id} 나 {@code target_role} 로 목록을 훑는
 * 메서드를 여기 만들지 마라 — 그 순간 이 테이블이 열거 가능해진다.</p>
 */
@Repository
public class MemberInvitationCodeRepository {

	private static final String FIND_BY_HASH = """
		SELECT id, teacher_id, target_role, max_claims, expires_at, revoked_at
		FROM member_invitation_codes
		WHERE code_hash = ?
		""";

	private static final String COUNT_CLAIMS = """
		SELECT count(*) FROM member_invitation_claims WHERE invitation_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberInvitationCodeRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<MemberInvitationCode> findByCodeHash(String codeHash) {
		if (codeHash == null) {
			return Optional.empty();
		}
		return jdbcTemplate.query(FIND_BY_HASH, rs -> rs.next()
			? Optional.of(new MemberInvitationCode(
				rs.getObject(1, UUID.class),
				rs.getObject(2, UUID.class),
				InvitationTargetRole.valueOf(rs.getString(3)),
				rs.getInt(4),
				rs.getObject(5, OffsetDateTime.class).toInstant(),
				instant(rs.getObject(6, OffsetDateTime.class))))
			: Optional.<MemberInvitationCode>empty(), codeHash);
	}

	/**
	 * 🔴 이 수는 <b>호출자에게 보이는 행만</b> 센다. {@code member_invitation_claims} 는
	 * 계정 소유 정책으로 격리되므로 남이 쓴 claim 은 0 으로 보인다 — 소진 판정의 최종 보장은
	 * {@code uq_member_invitation_claims_pair} 와 이 개수 비교가 아니라, 등록 트랜잭션이
	 * 관계 unique 에서 갈리는 지점이다. MB-38 로 등재했다.
	 */
	public int countVisibleClaims(UUID invitationId) {
		Integer count = jdbcTemplate.queryForObject(COUNT_CLAIMS, Integer.class, invitationId);
		return count == null ? 0 : count;
	}

	private java.time.Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
