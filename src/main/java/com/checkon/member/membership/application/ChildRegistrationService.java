package com.checkon.member.membership.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.application.ActivationCommandPort;
import com.checkon.member.common.error.ConstraintViolations;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.notification.NotificationPort;
import com.checkon.member.common.notification.NotificationRequest;
import com.checkon.member.common.notification.NotificationType;
import com.checkon.member.common.persistence.IdempotencyGuard;
import com.checkon.member.common.persistence.IdempotentOutcome;
import com.checkon.member.common.persistence.IdempotentPayload;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.ChildLinkView;
import com.checkon.member.integration.roster.dto.StudentIdentity;
import com.checkon.member.membership.infrastructure.persistence.ParentStudentRelationshipWriter;
import com.checkon.member.membership.infrastructure.persistence.StudentProfileLockRepository;

/**
 * 자녀 등록 (설계 §9-1). 이 PR 에서 가장 조심스러운 트랜잭션이다.
 *
 * <p>🔴 <b>관계 INSERT 가 활성화 UPDATE 보다 먼저다. 순서를 바꾸지 마라.</b>
 * V39 의 {@code member_student_activation_parent_scope_update} 는 범위 변수를 요구하고,
 * 그 범위는 {@code withVerifiedChildScope} 가 <b>관계를 되읽어 확인해야</b> 열린다.
 * UPDATE 를 먼저 하면 확인할 관계가 아직 없어 범위가 안 열리고, 그러면 UPDATE 는 예외가 아니라
 * <b>0행</b>으로 조용히 지나간다 — 학생은 영영 대기 상태로 남는다.</p>
 *
 * <p>🔴 사전 조회와 unique index 는 <b>둘 다</b> 필요하다. 앞의 것은 친절한 안내를 만들고,
 * {@code uq_parent_student_relationships_active_student}(V33:96-98)가 유일한 보장이다.
 * 사전 조회만 두면 동시 요청에서 뚫린다.</p>
 */
@Service
public class ChildRegistrationService {

	public static final String ROUTE_KEY = "POST /member/parents/me/children";
	public static final int CREATED = 201;

	private static final String UQ_ACTIVE_STUDENT =
		"uq_parent_student_relationships_active_student";

	private final RosterRelationshipPort rosterRelationships;
	private final StudentProfileLockRepository studentLocks;
	private final ParentStudentRelationshipWriter relationshipWriter;
	private final ActivationCommandPort activationCommand;
	private final ChildViewAssembler childViews;
	private final IdempotencyGuard idempotencyGuard;
	private final MemberDatabaseContext databaseContext;
	private final NotificationPort notificationPort;
	private final Clock clock;

	public ChildRegistrationService(
		RosterRelationshipPort rosterRelationships,
		StudentProfileLockRepository studentLocks,
		ParentStudentRelationshipWriter relationshipWriter,
		ActivationCommandPort activationCommand,
		ChildViewAssembler childViews,
		IdempotencyGuard idempotencyGuard,
		MemberDatabaseContext databaseContext,
		NotificationPort notificationPort,
		Clock clock
	) {
		this.rosterRelationships = rosterRelationships;
		this.studentLocks = studentLocks;
		this.relationshipWriter = relationshipWriter;
		this.activationCommand = activationCommand;
		this.childViews = childViews;
		this.idempotencyGuard = idempotencyGuard;
		this.databaseContext = databaseContext;
		this.notificationPort = notificationPort;
		this.clock = clock;
	}

	@Transactional
	public IdempotentOutcome register(MemberSubject subject, ChildRegistrationCommand command) {
		UUID parentProfileId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentProfileId);

		return idempotencyGuard.execute(subject.accountId(), ROUTE_KEY,
			command.idempotencyKey(), command.rawBody(),
			() -> new IdempotentPayload(CREATED,
				MemberResponse.of(link(subject, parentProfileId, command))));
	}

	private ChildRegistrationResultView link(
		MemberSubject subject,
		UUID parentProfileId,
		ChildRegistrationCommand command
	) {
		Instant now = clock.instant();
		StudentIdentity student = resolveStudent(command.studentPublicId());
		// 🔴 이 트랜잭션에서 그 학생을 건드리는 첫 문장이어야 한다. 뒤로 밀면 사전 조회와
		//    INSERT 사이에 다른 트랜잭션이 끼어들어 안내가 어긋난다.
		studentLocks.lock(student.studentProfileId());

		if (rosterRelationships.existsActiveParentLink(student.studentProfileId())) {
			// 정책상 보이는 것은 내 연결뿐이므로, 여기 도달했다면 내가 등록한 자녀다.
			throw new MemberException(MemberErrorCode.CHILD_ALREADY_LINKED,
				"the caller is already linked to this student",
				new AlreadyLinkedDetails(true));
		}
		UUID relationshipId = insertRelationship(parentProfileId, student.studentProfileId(), now);

		// 🔴 방금 같은 트랜잭션에서 만든 관계를 되읽어 범위를 연다. "방금 넣었으니 확인은 생략"
		//    할 문법적 방법이 없도록 세터를 두지 않았다(MemberDatabaseContext 참조).
		databaseContext.withVerifiedChildScope(parentProfileId, student.studentProfileId(),
			() -> activationCommand.activate(student.studentProfileId(), now));

		// 🔴 CHILD_LINKED 알림 — 이 PR 이 실제로 발행하는 유일한 알림.
		//    발행자·수신자가 같은 학부모 자신. source_id = relationshipId. 같은 관계 재등록은
		//    unique 로 조용히 무시된다(NotificationPort 계약).
		//    이 트랜잭션이 롤백되면 알림도 함께 사라진다(같은 트랜잭션).
		notificationPort.publish(new NotificationRequest(
			subject.accountId(),
			NotificationType.CHILD_LINKED,
			"자녀 " + student.alias() + " 등록 완료",
			null,
			student.studentProfileId(),
			null,
			"parent_student_relationship",
			relationshipId
		));

		ChildLinkView link = new ChildLinkView(student.studentProfileId(), student.accountId(),
			student.alias(), student.grade(), command.studentPublicId(), now);
		return new ChildRegistrationResultView(
			childViews.assemble(subject.accountId(), parentProfileId, link));
	}

	private StudentIdentity resolveStudent(String rawPublicId) {
		// 형식 오류는 400, 부재는 404 다(분기표 §3). 사전 확인과 같은 판정을 쓴다.
		String normalized = StudentPublicIds.requireNormalized(rawPublicId);
		Optional<StudentIdentity> student =
			rosterRelationships.findStudentByPublicId(normalized);
		return student.orElseThrow(() -> new MemberException(
			MemberErrorCode.RESOURCE_NOT_FOUND, "no student matches the given public id"));
	}

	private UUID insertRelationship(UUID parentProfileId, UUID studentProfileId, Instant now) {
		try {
			return relationshipWriter.insertActive(parentProfileId, studentProfileId, now);
		}
		catch (DataIntegrityViolationException exception) {
			// 🔴 제약 이름으로만 가른다. 메시지 substring 매칭은 DB 버전이 바뀌면 조용히 깨진다.
			String constraint = ConstraintViolations.constraintNameOf(exception);
			if (!UQ_ACTIVE_STUDENT.equals(constraint)) {
				// 🔴 알려지지 않은 제약은 삼키지 않는다. 409 로 뭉개면 진짜 장애가 숨는다.
				throw exception;
			}
			// 사전 조회를 통과하고 여기 왔다면 다른 학부모가 먼저 등록한 것이다.
			// 🔴 누구인지는 담지 않는다(설계 §7-2).
			throw new MemberException(MemberErrorCode.CHILD_ALREADY_LINKED,
				"another parent is already linked to this student",
				new AlreadyLinkedDetails(false));
		}
	}
}
