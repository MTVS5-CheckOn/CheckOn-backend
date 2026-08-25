package com.checkon.member.membership.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;
import com.checkon.member.membership.domain.InvitationTargetRole;
import com.checkon.member.membership.domain.MemberInvitationCode;
import com.checkon.member.membership.infrastructure.persistence.MemberInvitationCodeRepository;

/**
 * 초대 코드 검증. 학생·학부모 공통이며 역할은 {@link MemberSubject} 에서 온다.
 *
 * <p>🔴 판정 순서가 계약이다(분기표 §2·§5) — <b>역할 불일치는 404 지 403 이 아니다.</b>
 * 별도 코드로 알려주면 "그 코드는 존재하지만 네 역할이 아니다"가 되어 코드의 존재가 드러난다.</p>
 *
 * <p>🔴 없는 코드도 해시 계산을 <b>먼저</b> 수행한다. 조회 전에 형식으로 걸러 즉시 반환하면
 * 존재/부재의 응답 시간 차가 생겨 열거 단서가 된다(설계 §9-3).</p>
 */
@Service
public class InvitationVerificationService {

	private final MemberInvitationCodeRepository invitationCodes;
	private final RosterRelationshipPort rosterRelationships;
	private final MemberDatabaseContext databaseContext;
	private final Clock clock;

	public InvitationVerificationService(
		MemberInvitationCodeRepository invitationCodes,
		RosterRelationshipPort rosterRelationships,
		MemberDatabaseContext databaseContext,
		Clock clock
	) {
		this.invitationCodes = invitationCodes;
		this.rosterRelationships = rosterRelationships;
		this.databaseContext = databaseContext;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public InviteVerificationView verify(MemberSubject subject, String rawCode) {
		databaseContext.setCurrentAccount(subject.accountId());
		MemberInvitationCode code = resolve(subject, rawCode);
		TeacherSummaryView teacher = rosterRelationships.findTeacherSummary(code.teacherId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"invitation points to a teacher that is not readable"));
		return new InviteVerificationView(true, teacher, code.expiresAt());
	}

	/**
	 * 코드 해석 + 유효성 판정. 등록 경로도 이 메서드를 다시 부른다 —
	 * 🔴 검증 API 의 결과를 신뢰하지 않는다. 그 사이에 폐기·소진될 수 있다.
	 */
	MemberInvitationCode resolve(MemberSubject subject, String rawCode) {
		// 🔴 평문은 여기서 즉시 해시가 되고 그 뒤로 어디에도 남지 않는다 — 로그·예외 메시지 포함.
		String codeHash = InvitationCodeHasher.hash(rawCode);
		Optional<MemberInvitationCode> found = invitationCodes.findByCodeHash(codeHash);
		if (found.isEmpty()) {
			throw new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"no invitation matches the given code");
		}
		MemberInvitationCode code = found.get();
		// 🔴 역할 불일치를 404 로 되돌린다. 410 이나 403 을 쓰면 코드 존재가 드러난다.
		if (code.targetRole() != InvitationTargetRole.of(subject.role())) {
			throw new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"no invitation matches the given code");
		}
		Instant now = clock.instant();
		if (code.isExpiredOrRevoked(now)) {
			throw new MemberException(MemberErrorCode.INVITE_EXPIRED,
				"the invitation is expired or revoked");
		}
		return code;
	}
}
