package com.checkon.member.membership.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.roster.RosterRelationshipPort;

/**
 * 자녀 목록 조회.
 *
 * <p>🔴 자녀마다 <b>범위를 따로 연다.</b> 세션 변수는 한 번에 한 값이라 목록 전체를 한꺼번에
 * 열어두는 방법이 없다. 조립은 {@link ChildViewAssembler} 가 한다.</p>
 */
@Service
public class ChildQueryService {

	private final RosterRelationshipPort rosterRelationships;
	private final ChildViewAssembler childViews;
	private final MemberDatabaseContext databaseContext;

	public ChildQueryService(
		RosterRelationshipPort rosterRelationships,
		ChildViewAssembler childViews,
		MemberDatabaseContext databaseContext
	) {
		this.rosterRelationships = rosterRelationships;
		this.childViews = childViews;
		this.databaseContext = databaseContext;
	}

	@Transactional(readOnly = true)
	public List<ChildView> listChildren(MemberSubject subject) {
		UUID parentProfileId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentProfileId);

		// 🔴 DEACTIVATED 자녀도 목록에 남는다(분기표 :335). 상태로 표시할 뿐 숨기지 않는다.
		return rosterRelationships.findActiveChildren(parentProfileId).stream()
			.map(link -> childViews.assemble(subject.accountId(), parentProfileId, link))
			.toList();
	}
}
