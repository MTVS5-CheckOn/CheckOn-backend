package com.checkon.member.membership.application;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.checkon.member.auth.application.DisplayNameQueryPort;
import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.auth.infrastructure.persistence.MemberActivationRepository;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.integration.roster.dto.ChildLinkView;

/**
 * {@code Child} 응답 한 건을 만든다. 목록과 등록이 <b>같은 모양</b>을 내려보내게 한 곳에 둔다.
 *
 * <p>🔴 이름과 활성화 상태는 서로 <b>다른 범위 변수</b>를 요구한다(설계 §6-4-3) —
 * 표시 이름은 {@code scope_account_id}, 활성화는 {@code scope_student_id} 다.
 * 둘 다 확인을 삼킨 {@code withVerified*Scope} 로만 열린다.</p>
 */
@Component
public class ChildViewAssembler {

	private final DisplayNameQueryPort displayNames;
	private final MemberActivationRepository activationRepository;
	private final MemberDatabaseContext databaseContext;

	public ChildViewAssembler(
		DisplayNameQueryPort displayNames,
		MemberActivationRepository activationRepository,
		MemberDatabaseContext databaseContext
	) {
		this.displayNames = displayNames;
		this.activationRepository = activationRepository;
		this.databaseContext = databaseContext;
	}

	public ChildView assemble(UUID viewerAccountId, UUID parentProfileId, ChildLinkView link) {
		String name = databaseContext.withVerifiedAccountScope(
			viewerAccountId, link.accountId(),
			() -> displayNames.findDisplayName(link.accountId()).orElse(null));
		MemberActivationStatus status = databaseContext.withVerifiedChildScope(
			parentProfileId, link.studentProfileId(),
			() -> activationRepository.findStatus(link.studentProfileId()).orElse(null));
		// 🔴 teachers 키는 아예 넣지 않는다 — ChildView 주석 참조(MB-36).
		//    학부모 컨텍스트에는 teacher_student_relationships SELECT 정책이 없어서
		//    "없음"인지 "못 봄"인지 구분할 수 없고, 계약은 null 을 허용하지 않는다.
		return new ChildView(link.studentProfileId(), link.publicId(), name, link.grade(),
			status, link.linkedAt());
	}
}
