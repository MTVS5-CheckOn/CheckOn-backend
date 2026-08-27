package com.checkon.member.profile.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.application.MemberTeacherSummary;
import com.checkon.member.auth.infrastructure.persistence.MemberTeacherRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.naming.DisplayNameStore;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.integration.account.MemberAccountEmailReader;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.membership.application.ChildView;
import com.checkon.member.membership.application.ChildViewAssembler;
import com.checkon.member.profile.application.dto.ParentProfileResponse;
import com.checkon.member.profile.infrastructure.persistence.NotificationPreferenceRepository;

/**
 * 학부모 프로필 조회 · 알림 설정 변경.
 *
 * <p>🔴 {@code children} 은 PR4 의 {@link ChildViewAssembler} 를 재사용한다 — 자녀 목록·등록·
 * 프로필이 같은 모양이어야 한다(설계 §9). 자녀별 이름·강사 채우기 규칙을 여기서 다시 짜지 않는다.</p>
 *
 * <p>🔴 이메일은 {@code accounts.email} 이다 — RLS 대상이 아니다(설계 §4-3).</p>
 */
@Service
public class ParentProfileService {

	private final DisplayNameStore displayNameStore;
	private final MemberTeacherRepository teacherRepository;
	private final MemberAccountEmailReader emailReader;
	private final RosterRelationshipPort rosterRelationships;
	private final ChildViewAssembler childViews;
	private final NotificationPreferenceRepository preferenceRepository;
	private final MemberDatabaseContext databaseContext;
	private final MemberProfileProperties properties;
	private final Clock clock;

	public ParentProfileService(
		DisplayNameStore displayNameStore,
		MemberTeacherRepository teacherRepository,
		MemberAccountEmailReader emailReader,
		RosterRelationshipPort rosterRelationships,
		ChildViewAssembler childViews,
		NotificationPreferenceRepository preferenceRepository,
		MemberDatabaseContext databaseContext,
		MemberProfileProperties properties,
		Clock clock
	) {
		this.displayNameStore = displayNameStore;
		this.teacherRepository = teacherRepository;
		this.emailReader = emailReader;
		this.rosterRelationships = rosterRelationships;
		this.childViews = childViews;
		this.preferenceRepository = preferenceRepository;
		this.databaseContext = databaseContext;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ParentProfileResponse getProfile(MemberSubject subject) {
		UUID parentProfileId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentProfileId);

		String name = displayNameStore.read(subject.accountId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.INTERNAL,
				"display name row is missing"));
		String email = emailReader.findEmail(subject.accountId()).orElse(null);
		List<MemberTeacherSummary> teachers = teacherRepository.findForParent(parentProfileId);
		// 🔴 자녀는 PR4 assembler 를 재사용 — 채우기 규칙(이름·강사)이 동일하다.
		List<ChildView> children = rosterRelationships.findActiveChildren(parentProfileId).stream()
			.map(link -> childViews.assemble(subject.accountId(), parentProfileId, link))
			.toList();
		boolean notificationsEnabled = preferenceRepository.find(subject.accountId())
			.orElse(properties.defaultEnabled());

		return new ParentProfileResponse(
			parentProfileId, name, email, children, teachers, notificationsEnabled);
	}

	@Transactional
	public void updateNotificationPreference(MemberSubject subject, boolean enabled) {
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(subject.requireParentProfileId());
		Instant now = clock.instant();
		preferenceRepository.upsert(subject.accountId(), enabled, now);
	}
}
