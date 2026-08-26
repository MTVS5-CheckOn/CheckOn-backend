package com.checkon.member.membership.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code member_invitation_codes} 한 행. 🔴 평문 코드는 담지 않는다 — 해시만 저장·비교한다.
 *
 * @param maxClaims 이 코드를 쓸 수 있는 계정 수. MB-04 확정은 1 이지만 <b>값은 DB 가 정한다</b>
 *                  (코드 규칙 §2 — 코드에 고정하면 초대마다 다르게 줄 수 없다)
 */
public record MemberInvitationCode(
	UUID id,
	UUID teacherId,
	InvitationTargetRole targetRole,
	int maxClaims,
	Instant expiresAt,
	Instant revokedAt
) {

	/** 만료·폐기 판정. 🔴 시각은 호출자가 주입한 {@code Clock} 에서 온다. */
	public boolean isExpiredOrRevoked(Instant now) {
		return revokedAt != null || !expiresAt.isAfter(now);
	}
}
