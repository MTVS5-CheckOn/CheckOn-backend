package com.checkon.member.profile.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.application.MemberTeacherSummary;
import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.auth.infrastructure.persistence.MemberActivationRepository;
import com.checkon.member.auth.infrastructure.persistence.MemberTeacherRepository;
import com.checkon.member.auth.infrastructure.persistence.PublicStudentIdRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.naming.DisplayNameStore;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.profile.application.dto.StudentProfileResponse;
import com.checkon.member.profile.infrastructure.persistence.NotificationPreferenceRepository;
import com.checkon.member.profile.infrastructure.persistence.StudentProfileReader;

import java.util.List;

/**
 * 학생 프로필 조회 · 알림 설정 변경.
 *
 * <p>🔴 이름은 {@link DisplayNameStore} 만 읽는다. {@code student_profiles.alias} 는 강사가
 * 관리하는 로스터 표시명이라 여기에 나오지 않는다(설계 §1-4 ①).</p>
 *
 * <p>🔴 {@code parentLinked} 는 <b>{@code activationStatus == ACTIVE}</b> 로 파생한다 —
 * {@code parent_student_relationships} 에는 학생 SELECT 정책이 없다(V38 전수 #6).</p>
 */
@Service
public class StudentProfileService {

	private final DisplayNameStore displayNameStore;
	private final MemberActivationRepository activationRepository;
	private final PublicStudentIdRepository publicStudentIdRepository;
	private final MemberTeacherRepository teacherRepository;
	private final StudentProfileReader studentProfileReader;
	private final NotificationPreferenceRepository preferenceRepository;
	private final MemberDatabaseContext databaseContext;
	private final MemberProfileProperties properties;
	private final Clock clock;

	public StudentProfileService(
		DisplayNameStore displayNameStore,
		MemberActivationRepository activationRepository,
		PublicStudentIdRepository publicStudentIdRepository,
		MemberTeacherRepository teacherRepository,
		StudentProfileReader studentProfileReader,
		NotificationPreferenceRepository preferenceRepository,
		MemberDatabaseContext databaseContext,
		MemberProfileProperties properties,
		Clock clock
	) {
		this.displayNameStore = displayNameStore;
		this.activationRepository = activationRepository;
		this.publicStudentIdRepository = publicStudentIdRepository;
		this.teacherRepository = teacherRepository;
		this.studentProfileReader = studentProfileReader;
		this.preferenceRepository = preferenceRepository;
		this.databaseContext = databaseContext;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public StudentProfileResponse getProfile(MemberSubject subject) {
		UUID studentProfileId = subject.requireStudentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(studentProfileId);

		String name = displayNameStore.read(subject.accountId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.INTERNAL,
				"display name row is missing"));
		String publicId = publicStudentIdRepository.findByStudent(studentProfileId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.INTERNAL,
				"public student id row is missing"));
		MemberActivationStatus status = activationRepository.findStatus(studentProfileId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.INTERNAL,
				"activation row is missing"));
		// 🔴 grade 는 nullable. student_profiles.grade 가 NULL 이면 그대로 null 을 내보낸다 —
		//    1 로 채우지 마라(계약 required 불일치는 open item · 지어내지 않는다).
		Integer grade = studentProfileReader.findGrade(studentProfileId).orElse(null);
		List<MemberTeacherSummary> teachers = teacherRepository.findForStudent(studentProfileId);
		boolean notificationsEnabled = preferenceRepository.find(subject.accountId())
			.orElse(properties.defaultEnabled());
		// 🔴 parentLinked = ACTIVE 파생. parent_student_relationships 를 학생이 못 본다.
		boolean parentLinked = status == MemberActivationStatus.ACTIVE;

		return new StudentProfileResponse(
			studentProfileId, publicId, name, grade, status, parentLinked,
			teachers, notificationsEnabled);
	}

	@Transactional
	public void updateNotificationPreference(MemberSubject subject, boolean enabled) {
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentStudent(subject.requireStudentProfileId());
		Instant now = clock.instant();
		preferenceRepository.upsert(subject.accountId(), enabled, now);
	}
}
