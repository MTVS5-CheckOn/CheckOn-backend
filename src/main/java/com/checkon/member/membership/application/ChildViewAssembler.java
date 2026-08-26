package com.checkon.member.membership.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.checkon.member.auth.application.DisplayNameQueryPort;
import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.auth.infrastructure.persistence.MemberActivationRepository;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.ChildLinkView;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;

/**
 * {@code Child} 응답 한 건을 만든다. 목록과 등록이 <b>같은 모양</b>을 내려보내게 한 곳에 둔다.
 *
 * <p>🔴 이름·활성화·강사는 서로 <b>다른 범위 변수</b>를 요구한다(설계 §6-4-3) —
 * 표시 이름은 {@code scope_account_id}, 활성화/강사는 {@code scope_student_id} 다.
 * 셋 다 확인을 삼킨 {@code withVerified*Scope} 로만 열린다. 자녀 활성화와 강사 조회는 같은
 * {@code scope_student_id} 를 요구하므로 <b>한 번의 {@code withVerifiedChildScope}</b> 안에서
 * 함께 읽는다 — 스코프를 두 번 여닫으면 라운드트립이 늘고 확인 쿼리도 이중이 된다.</p>
 */
@Component
public class ChildViewAssembler {

	private final DisplayNameQueryPort displayNames;
	private final MemberActivationRepository activationRepository;
	private final RosterRelationshipPort rosterRelationships;
	private final MemberDatabaseContext databaseContext;

	public ChildViewAssembler(
		DisplayNameQueryPort displayNames,
		MemberActivationRepository activationRepository,
		RosterRelationshipPort rosterRelationships,
		MemberDatabaseContext databaseContext
	) {
		this.displayNames = displayNames;
		this.activationRepository = activationRepository;
		this.rosterRelationships = rosterRelationships;
		this.databaseContext = databaseContext;
	}

	public ChildView assemble(UUID viewerAccountId, UUID parentProfileId, ChildLinkView link) {
		String name = databaseContext.withVerifiedAccountScope(
			viewerAccountId, link.accountId(),
			() -> displayNames.findDisplayName(link.accountId()).orElse(null));
		// 🔴 활성화 상태와 강사 목록은 둘 다 scope_student_id 를 요구한다.
		//    한 번의 withVerifiedChildScope 안에서 함께 읽는다 — MB-36 정책이 여기서 활성화된다.
		ChildScopedFields scoped = databaseContext.withVerifiedChildScope(
			parentProfileId, link.studentProfileId(),
			() -> new ChildScopedFields(
				activationRepository.findStatus(link.studentProfileId()).orElse(null),
				rosterRelationships.findTeachersOfChild(link.studentProfileId())));
		return new ChildView(link.studentProfileId(), link.publicId(), name, link.grade(),
			scoped.status(), scoped.teachers(), link.linkedAt());
	}

	private record ChildScopedFields(
		MemberActivationStatus status,
		List<TeacherSummaryView> teachers
	) {
	}
}
