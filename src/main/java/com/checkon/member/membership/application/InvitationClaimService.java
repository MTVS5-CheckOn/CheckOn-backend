package com.checkon.member.membership.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.IdempotencyGuard;
import com.checkon.member.common.persistence.IdempotentOutcome;
import com.checkon.member.common.persistence.IdempotentPayload;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.MemberRole;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;
import com.checkon.member.membership.domain.MemberInvitationCode;
import com.checkon.member.membership.infrastructure.persistence.MemberInvitationClaimRepository;
import com.checkon.member.membership.infrastructure.persistence.ParentTeacherRelationshipWriter;
import com.checkon.member.membership.infrastructure.persistence.TeacherStudentRelationshipWriter;

/**
 * 초대 코드 등록. 학생은 강사↔학생, 학부모는 학부모↔강사 관계를 만든다.
 *
 * <p>🔴 <b>"코드를 이미 썼다"와 "관계가 이미 있다"는 다른 사건이다.</b> 제약이 다르다 —
 * {@code uq_member_invitation_claims_pair} 와 관계 unique 다. 하나로 뭉치면 학부모가 새 코드로
 * 재연결을 시도했을 때 "이미 쓴 코드"라는 틀린 안내를 받는다. MB-04 확정에 따라 <b>둘 다
 * 200 + 기존 TeacherSummary</b> 지만 서로 다른 문장을 타고, <b>남기는 행이 다르다</b>:
 * 같은 코드 재제출은 claim 을 추가하지 않고, 새 코드는 claim 을 하나 더 남긴다.</p>
 *
 * <p>🔴 <b>두 충돌 모두 예외로 잡지 않고 {@code ON CONFLICT} 로 이름을 지목해 무시한다.</b>
 * PostgreSQL 은 제약 위반이 나면 트랜잭션을 abort 시켜 이후 문장을 전부 거절한다 —
 * 「23505 를 catch 하고 계속 진행」은 같은 트랜잭션에서 성립하지 않는다. 지목하지 않은 제약은
 * 그대로 터져 500 이 되므로 뭉개기가 아니다(각 writer 의 주석 참조).</p>
 *
 * <p>🔴 <b>다른 계정이 코드를 소진했는지는 판정할 수 없다.</b>
 * {@code member_invitation_claims} 는 계정 소유 정책으로 격리돼 남의 claim 이 0 으로 보인다.
 * {@code max_claims} 를 강제할 DB 제약도 없다(pair unique 는 같은 계정의 재사용만 막는다).
 * 그래서 {@code 409 INVITE_ALREADY_CLAIMED} 의 "다른 계정 소진" 분기는 이 PR 에서
 * <b>구현하지 않았다</b> — 없는 판정을 있는 척하지 않는다. MB-38 로 등재했다.</p>
 */
@Service
public class InvitationClaimService {

	public static final String STUDENT_ROUTE_KEY = "POST /member/students/me/invitations";
	public static final String PARENT_ROUTE_KEY = "POST /member/parents/me/invitations";
	public static final int CREATED = 201;
	public static final int ALREADY_LINKED = 200;

	private final InvitationVerificationService verification;
	private final MemberInvitationClaimRepository claims;
	private final TeacherStudentRelationshipWriter teacherStudentLinks;
	private final ParentTeacherRelationshipWriter parentTeacherLinks;
	private final RosterRelationshipPort rosterRelationships;
	private final IdempotencyGuard idempotencyGuard;
	private final MemberDatabaseContext databaseContext;
	private final Clock clock;

	public InvitationClaimService(
		InvitationVerificationService verification,
		MemberInvitationClaimRepository claims,
		TeacherStudentRelationshipWriter teacherStudentLinks,
		ParentTeacherRelationshipWriter parentTeacherLinks,
		RosterRelationshipPort rosterRelationships,
		IdempotencyGuard idempotencyGuard,
		MemberDatabaseContext databaseContext,
		Clock clock
	) {
		this.verification = verification;
		this.claims = claims;
		this.teacherStudentLinks = teacherStudentLinks;
		this.parentTeacherLinks = parentTeacherLinks;
		this.rosterRelationships = rosterRelationships;
		this.idempotencyGuard = idempotencyGuard;
		this.databaseContext = databaseContext;
		this.clock = clock;
	}

	@Transactional
	public IdempotentOutcome claim(MemberSubject subject, InvitationClaimCommand command) {
		databaseContext.setCurrentAccount(subject.accountId());
		// 🔴 역할 주체까지 연다. 계정만 열면 관계 INSERT 정책이 거절하고 조회는 0행이 된다.
		openRoleSubject(subject);

		String routeKey = subject.role() == MemberRole.STUDENT
			? STUDENT_ROUTE_KEY : PARENT_ROUTE_KEY;
		return idempotencyGuard.execute(subject.accountId(), routeKey,
			command.idempotencyKey(), command.rawBody(),
			() -> attach(subject, command));
	}

	private void openRoleSubject(MemberSubject subject) {
		if (subject.role() == MemberRole.STUDENT) {
			databaseContext.setCurrentStudent(subject.requireStudentProfileId());
			return;
		}
		databaseContext.setCurrentParent(subject.requireParentProfileId());
	}

	private IdempotentPayload attach(MemberSubject subject, InvitationClaimCommand command) {
		// 🔴 검증 API 의 결과를 신뢰하지 않는다. 그 사이 폐기·만료됐을 수 있다.
		MemberInvitationCode code = verification.resolve(subject, command.code());
		TeacherSummaryView teacher = rosterRelationships.findTeacherSummary(code.teacherId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"invitation points to a teacher that is not readable"));

		// 🔴 claim 기록과 관계 생성은 별개 사건이다. 내가 이미 쓴 코드여도 관계가 없으면 만든다 —
		//    앞선 시도가 관계 생성 직전에 실패했을 수 있다.
		claims.insertIfAbsent(code.id(), subject.accountId(), clock.instant());
		boolean created = attachRelationship(subject, code.teacherId());
		// 관계를 새로 만들었으면 201, 이미 있었으면 200 이다 — MB-04 멱등.
		return new IdempotentPayload(created ? CREATED : ALREADY_LINKED,
			MemberResponse.of(teacher));
	}

	/** @return 관계를 새로 만들었으면 {@code true}, 이미 있어서 무시됐으면 {@code false} */
	private boolean attachRelationship(MemberSubject subject, UUID teacherId) {
		Instant now = clock.instant();
		if (subject.role() == MemberRole.STUDENT) {
			return teacherStudentLinks.insertActiveIfAbsent(
				teacherId, subject.requireStudentProfileId(), now);
		}
		return parentTeacherLinks.insertActiveIfAbsent(
			subject.requireParentProfileId(), teacherId, now);
	}
}
