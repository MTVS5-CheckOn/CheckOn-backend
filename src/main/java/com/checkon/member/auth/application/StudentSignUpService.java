package com.checkon.member.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.auth.infrastructure.persistence.MemberActivationRepository;
import com.checkon.member.auth.infrastructure.persistence.MemberDisplayNameRepository;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.integration.account.AccountWriterAdapter;
import com.checkon.member.integration.account.MemberAccountCreation;
import com.checkon.member.common.security.MemberRole;
import com.checkon.member.integration.roster.RosterProfileWriterAdapter;

/**
 * 학생 가입. 한 트랜잭션 안에서 계정·프로필·공개 ID·활성화 레코드를 함께 만든다.
 *
 * <p>🔴 컨텍스트 설정 순서가 계약이다 —
 * {@code accounts} flush 로 accountId 를 확정한 뒤 {@code setCurrentAccount} 를 부르고,
 * 그 다음에 프로필을 넣는다. {@code student_profiles} 에는 지금 RLS 가 없지만 학부모 경로와
 * 순서를 통일한다. 나중에 누가 RLS 를 켰을 때 학생 경로만 조용히 깨지는 걸 막는다.</p>
 */
@Service
public class StudentSignUpService {

	private final AccountWriterAdapter accountWriter;
	private final RosterProfileWriterAdapter rosterWriter;
	private final PublicStudentIdIssuer publicStudentIdIssuer;
	private final MemberActivationRepository activationRepository;
	private final MemberDisplayNameRepository displayNameRepository;
	private final MemberDatabaseContext databaseContext;
	private final Clock clock;

	public StudentSignUpService(
		AccountWriterAdapter accountWriter,
		RosterProfileWriterAdapter rosterWriter,
		PublicStudentIdIssuer publicStudentIdIssuer,
		MemberActivationRepository activationRepository,
		MemberDisplayNameRepository displayNameRepository,
		MemberDatabaseContext databaseContext,
		Clock clock
	) {
		this.accountWriter = accountWriter;
		this.rosterWriter = rosterWriter;
		this.publicStudentIdIssuer = publicStudentIdIssuer;
		this.activationRepository = activationRepository;
		this.displayNameRepository = displayNameRepository;
		this.databaseContext = databaseContext;
		this.clock = clock;
	}

	@Transactional
	public MemberSignUpResult signUp(MemberSignUpCommand command) {
		Instant now = Instant.now(clock);
		String name = command.name().strip();

		// 1. 계정 — flush 로 accountId 를 확정한다(2번이 이 값을 쓴다).
		MemberAccountCreation account =
			accountWriter.create(command.email(), command.password(), MemberRole.STUDENT, now);

		// 2. 🔴 RLS 주체를 먼저 연다. 3·4·5 번보다 뒤로 옮기면 INSERT 가 거절된다.
		databaseContext.setCurrentAccount(account.accountId());

		// 3. 프로필. alias 에는 표시 이름과 같은 값을 초기값으로만 넣는다 —
		//    이후 member API 는 member_display_names 만 읽고 쓴다.
		UUID studentProfileId =
			rosterWriter.insertStudentProfile(account.accountId(), name, command.grade(), now);
		displayNameRepository.insert(account.accountId(), name, now);

		// 4. 🔴 학생 주체를 연다. member_student_activation_member_student_insert(V38:357-362)가
		//    current_checkon_student_id() = student_id 를 요구한다. 이 줄이 없으면 5번이
		//    RLS 로 거절된다(INSERT 는 조용히 넘어가지 않고 예외가 난다).
		databaseContext.setCurrentStudent(studentProfileId);

		// 5. 공개 학생 ID. 상한 초과면 예외가 올라가 트랜잭션 전체가 롤백된다.
		String publicId = publicStudentIdIssuer.issue(studentProfileId, now);

		// 6. 활성화 대기. 학부모가 자녀로 등록해야 ACTIVE 가 된다.
		activationRepository.insertPending(studentProfileId, now);

		return new MemberSignUpResult(account.accountId(), MemberRole.STUDENT.name(),
			publicId, MemberActivationStatus.PENDING_PARENT_LINK);
	}
}
