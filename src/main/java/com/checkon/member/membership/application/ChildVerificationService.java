package com.checkon.member.membership.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.StudentIdentity;
import com.checkon.member.membership.domain.ChildVerificationReason;

/**
 * 자녀 등록 전 사전 확인. <b>안내용</b>이며 상태를 바꾸지 않는다.
 *
 * <p>🔴 호출부가 레이트 리미터를 먼저 통과시킨다. 이 경로는 공개 학생 ID 하나로 남의 학생
 * 존재 여부를 묻기 때문에 열거 공격의 표적이다(설계 §9-3).</p>
 */
@Service
public class ChildVerificationService {

	private final RosterRelationshipPort rosterRelationships;
	private final MemberDatabaseContext databaseContext;

	public ChildVerificationService(
		RosterRelationshipPort rosterRelationships,
		MemberDatabaseContext databaseContext
	) {
		this.rosterRelationships = rosterRelationships;
		this.databaseContext = databaseContext;
	}

	@Transactional(readOnly = true)
	public ChildVerificationView verify(MemberSubject subject, String rawPublicId) {
		UUID parentProfileId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentProfileId);

		// 형식 오류는 400, 부재는 404 다(분기표 §3). 판정은 StudentPublicIds 한 곳에서 한다.
		String normalized = StudentPublicIds.requireNormalized(rawPublicId);
		Optional<StudentIdentity> student =
			rosterRelationships.findStudentByPublicId(normalized);
		if (student.isEmpty()) {
			throw new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"no student matches the given public id");
		}

		StudentIdentity identity = student.get();
		// 🔴 보이는 것은 내 연결뿐이다. 다른 학부모의 연결은 정책이 가린다(MB-37) —
		//    그 경우 여기서는 registrable=true 로 보이고 등록에서 409 로 갈린다.
		if (rosterRelationships.existsActiveParentLink(identity.studentProfileId())) {
			return new ChildVerificationView(false, null, null,
				ChildVerificationReason.ALREADY_LINKED);
		}
		// 🔴 원본 alias 를 그대로 내보내지 않는다. 마스킹은 순수 함수 한 곳에서만 한다.
		return new ChildVerificationView(true, NameMasker.mask(identity.alias()),
			identity.grade(), null);
	}
}
