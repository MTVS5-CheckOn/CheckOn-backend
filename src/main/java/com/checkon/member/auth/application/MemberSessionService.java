package com.checkon.member.auth.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.auth.infrastructure.persistence.MemberActivationRepository;
import com.checkon.member.auth.infrastructure.persistence.MemberDisplayNameRepository;
import com.checkon.member.auth.infrastructure.persistence.MemberTeacherRepository;
import com.checkon.member.auth.infrastructure.persistence.PublicStudentIdRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberRole;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.profile.application.MemberProfileProperties;
import com.checkon.member.profile.infrastructure.persistence.NotificationPreferenceRepository;

/**
 * 앱 bootstrap 과 활성화 상태 폴링을 담당한다.
 *
 * <p>🔴 모든 조회는 {@code @Transactional} 안에서 돈다. RLS 세션 변수가
 * {@code set_config(..., true)} 로 <b>트랜잭션 로컬</b>이라, 트랜잭션 밖에서 읽으면
 * 예외가 아니라 <b>조용히 빈 결과</b>가 나온다.</p>
 *
 * <p>🔴 계정 주체만 여는 것으로는 부족하다. 테이블마다 요구하는 함수가 다르다:</p>
 * <pre>
 * member_display_names            current_checkon_account_id()   (V39:67-71)
 * member_student_activation       current_checkon_student_id()   (V38:349-354)
 * teacher_student_relationships   current_checkon_student_id()   (V38:125-130)
 * parent_teacher_relationships    current_checkon_parent_id()    (V38:85-90)
 * </pre>
 * <p>하나라도 빠뜨리면 예외 없이 <b>빈 배열</b>이나 "행이 없다"가 나온다. 그래서 역할별로
 * 필요한 주체를 모두 연 뒤에 읽는다.</p>
 */
@Service
public class MemberSessionService {

	private final MemberDisplayNameRepository displayNameRepository;
	private final MemberActivationRepository activationRepository;
	private final PublicStudentIdRepository publicStudentIdRepository;
	private final MemberTeacherRepository teacherRepository;
	private final MemberDatabaseContext databaseContext;
	private final NotificationPreferenceRepository preferenceRepository;
	private final MemberProfileProperties profileProperties;

	public MemberSessionService(
		MemberDisplayNameRepository displayNameRepository,
		MemberActivationRepository activationRepository,
		PublicStudentIdRepository publicStudentIdRepository,
		MemberTeacherRepository teacherRepository,
		MemberDatabaseContext databaseContext,
		NotificationPreferenceRepository preferenceRepository,
		MemberProfileProperties profileProperties
	) {
		this.displayNameRepository = displayNameRepository;
		this.activationRepository = activationRepository;
		this.publicStudentIdRepository = publicStudentIdRepository;
		this.teacherRepository = teacherRepository;
		this.databaseContext = databaseContext;
		this.preferenceRepository = preferenceRepository;
		this.profileProperties = profileProperties;
	}

	@Transactional(readOnly = true)
	public MemberSessionView session(MemberSubject subject) {
		openSubjectContext(subject);

		String name = displayNameRepository.find(subject.accountId())
			// 🔴 빈 문자열로 때우지 않는다. 계약 :1733 이 name 을 required 로 뒀으니
			//    없는 상태로 200 을 내면 프론트가 조용히 빈 이름을 렌더한다.
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.INTERNAL, "display name row is missing"));

		// 🔴 MB-32 CLOSED — V41 이 원본 테이블을 만들었다. 부재 시 Settings 기본값.
		boolean notificationsEnabled = preferenceRepository.find(subject.accountId())
			.orElse(profileProperties.defaultEnabled());

		if (subject.role() == MemberRole.PARENT) {
			return new MemberSessionView(
				subject.accountId(),
				MemberRole.PARENT.name(),
				name,
				null,
				subject.parentProfileId(),
				// 🔴 학부모는 활성화·공개 ID 가 "없는" 값이다. 키는 두고 값만 null.
				null,
				null,
				teacherRepository.findForParent(subject.requireParentProfileId()),
				notificationsEnabled);
		}

		UUID studentProfileId = subject.requireStudentProfileId();
		return new MemberSessionView(
			subject.accountId(),
			MemberRole.STUDENT.name(),
			name,
			studentProfileId,
			null,
			subject.activationStatus(),
			publicStudentIdRepository.findByStudent(studentProfileId).orElse(null),
			// 🔴 대기 학생은 강사 관계가 아직 없다. 예외가 아니라 빈 배열이 맞다.
			teacherRepository.findForStudent(studentProfileId),
			notificationsEnabled);
	}

	/**
	 * 대기 화면 폴링용. 대기 학생도 부를 수 있는 2개 중 하나다(MB-02 CONFIRMED).
	 *
	 * <p>🔴 활성화 행이 없으면 {@code PENDING_PARENT_LINK} 로 지어내지 않는다. 가입이 깨졌다는
	 * 신호이므로 500 으로 드러낸다 — guard 도 같은 상태를 403 으로 막는다.</p>
	 */
	@Transactional(readOnly = true)
	public StudentActivationView activationStatus(MemberSubject subject) {
		openSubjectContext(subject);

		UUID studentProfileId = subject.requireStudentProfileId();
		MemberActivationStatus status = activationRepository.findStatus(studentProfileId)
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.INTERNAL, "activation row is missing"));
		Instant activatedAt = activationRepository.findActivatedAt(studentProfileId).orElse(null);
		String publicId = publicStudentIdRepository.findByStudent(studentProfileId)
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.INTERNAL, "public student id row is missing"));

		return new StudentActivationView(status, publicId, activatedAt);
	}

	/** 🔴 계정 + 역할 주체를 함께 연다. 둘 중 하나만 열면 조용히 빈 결과가 나온다. */
	private void openSubjectContext(MemberSubject subject) {
		databaseContext.setCurrentAccount(subject.accountId());
		if (subject.role() == MemberRole.PARENT) {
			databaseContext.setCurrentParent(subject.requireParentProfileId());
		} else {
			databaseContext.setCurrentStudent(subject.requireStudentProfileId());
		}
	}
}
