package com.checkon.member.auth.application;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.auth.infrastructure.persistence.MemberActivationRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.security.MemberPrincipal;
import com.checkon.member.common.security.MemberProfileDirectory;
import com.checkon.member.common.security.MemberRole;
import com.checkon.member.common.security.MemberSubject;

/**
 * 주체 조회를 <b>한 트랜잭션</b> 안에서 RLS 컨텍스트와 함께 수행한다.
 *
 * <p>🔴 왜 별도 빈인가 — {@code parent_profiles}(V33:105-106) 와
 * {@code member_student_activation}(V38) 은 {@code ENABLE + FORCE ROW LEVEL SECURITY} 다.
 * 컨텍스트 없이 읽으면 예외가 아니라 <b>조용히 0행</b>이 나온다(실측). 그 결과는
 * 학부모 전 요청 401, 학생 전 요청 403 이다 — 인증 자체가 깨진 것처럼 보여 원인을 찾기 어렵다.</p>
 *
 * <p>🔴 왜 {@code common/security} 가 아니라 {@code auth/application} 인가 —
 * 코드 규칙 G5 가 {@code @Transactional} 을 application 계층에만 허용한다. 설계 §11 표는
 * <i>"{@code MemberSubjectResolver} 에 {@code @Transactional}: 없음 — 단일 조회라 트랜잭션이
 * 필요 없다"</i> 라고 적었지만 <b>그 전제가 거짓이다</b>: 조회가 2회 이상이고, 그 사이에
 * RLS 컨텍스트가 유지돼야 하는데 {@code set_config(..., true)} 는 트랜잭션 로컬이다.
 * 트랜잭션이 없으면 JdbcTemplate 호출마다 auto-commit 트랜잭션이 새로 열려 컨텍스트가 증발한다.</p>
 *
 * <p>🔴 왜 {@code MemberSubjectResolver} 에 직접 붙이지 않는가 — 그 클래스는
 * {@code HandlerMethodArgumentResolver} 로 등록되고 내부 호출이 섞인다. 프록시가 언제
 * 끼는지에 의존하는 코드는 나중에 조용히 깨진다. 경계를 별도 빈으로 분리해 눈에 보이게 둔다.</p>
 *
 * <p>🔴 {@code set_config(..., true)} 는 트랜잭션 로컬이라 이 메서드가 끝나면 사라진다.
 * 호출부가 그 값에 기대면 안 된다 — 뒤에 오는 서비스는 자기 트랜잭션에서 다시 연다.</p>
 */
@Component
public class MemberSubjectLoader {

	private final MemberProfileDirectory profileDirectory;
	private final MemberActivationRepository activationRepository;
	private final MemberDatabaseContext databaseContext;

	public MemberSubjectLoader(
		MemberProfileDirectory profileDirectory,
		MemberActivationRepository activationRepository,
		MemberDatabaseContext databaseContext
	) {
		this.profileDirectory = profileDirectory;
		this.activationRepository = activationRepository;
		this.databaseContext = databaseContext;
	}

	@Transactional(readOnly = true)
	public MemberSubject load(MemberPrincipal principal) {
		// 1. accountId 만으로 프로필을 찾는다. parent_profiles_member_self_select(V38:72-76)가
		//    account_id 분기를 둔 이유가 정확히 이것이다.
		databaseContext.setCurrentAccount(principal.accountId());

		if (principal.role() == MemberRole.PARENT) {
			UUID parentProfileId = profileDirectory.findParentProfileId(principal.accountId())
				.orElseThrow(() -> new MemberException(
					MemberErrorCode.AUTHENTICATION_REQUIRED, "parent profile is missing"));
			return new MemberSubject(principal.accountId(), MemberRole.PARENT,
				principal.sessionId(), null, parentProfileId, null);
		}

		UUID studentProfileId = profileDirectory.findStudentProfileId(principal.accountId())
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.AUTHENTICATION_REQUIRED, "student profile is missing"));
		// 2. 🔴 활성화 조회 전에 학생 주체를 연다.
		//    member_student_activation_member_student_select(V38:349-354)가
		//    current_checkon_student_id() 를 요구한다. 이 줄이 없으면 조용히 0행이고,
		//    guard 가 fail-closed 라 모든 학생이 403 이 된다.
		databaseContext.setCurrentStudent(studentProfileId);
		MemberActivationStatus activationStatus =
			activationRepository.findStatus(studentProfileId).orElse(null);

		return new MemberSubject(principal.accountId(), MemberRole.STUDENT,
			principal.sessionId(), studentProfileId, null, activationStatus);
	}
}
