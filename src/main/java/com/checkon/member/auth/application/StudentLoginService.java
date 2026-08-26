package com.checkon.member.auth.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.auth.domain.PublicStudentId;
import com.checkon.member.auth.infrastructure.persistence.PublicStudentIdRepository;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.integration.account.MemberAccountEmailReader;
import com.checkon.member.integration.account.MemberAuthentication;
import com.checkon.member.integration.account.MemberLoginAdapter;
import com.checkon.member.integration.roster.RosterProfileWriterAdapter;

/**
 * 학생 로그인 (MB-01). 공개 학생 ID + 비밀번호다.
 *
 * <pre>
 * 공개 ID 정규화 → member_student_public_ids → student_profiles.account_id → accounts.email
 *   → 기존 LoginService.login(email, password)
 * </pre>
 *
 * <p>🔴 {@code account} 패키지는 한 줄도 고치지 않는다. 앞단에서 변환만 한다.</p>
 *
 * <p>🔴 <b>열거 방어</b> — 공개 ID 미일치·계정 미연결에도 즉시 반환하지 않는다.
 * 더미 해시로 BCrypt 시간을 태운 뒤 비밀번호 불일치와 <b>같은</b>
 * {@code 401 INVALID_CREDENTIALS} 를 낸다. 세 경우를 구분하는 순간 공개 ID 열거가 가능해진다.</p>
 *
 * <p>🔴 <b>예외</b> — 정지된 계정만은 구분해서 {@code 401 ACCOUNT_NOT_ACTIVE} 로 나간다.
 * 계약 {@code member-api.yaml:153} 이 그렇게 적었고, 그 지점에 도달했다는 건 이미 비밀번호가
 * 맞았다는 뜻이라 열거 위험이 없다. 번역은 {@code MemberLoginAdapter} 가 한다.</p>
 *
 * <p>🔴 <b>G15-EXEMPT(member_student_public_ids, student_profiles, accounts)</b> —
 * 이 서비스는 RLS 컨텍스트를 열지 않는다. 열 수가 없다: 로그인 <b>전</b>이라 주체가 아직
 * 확정되지 않았고, 여는 순간 인자로 받은 값을 그대로 믿는 우회로가 된다.
 * 읽는 테이블 셋은 전부 RLS 밖이다 — 공개 ID 표는 소유자가 조회자가 아니라서(MB-30),
 * 나머지 둘은 원래 RLS 가 없다. G15-b 가 이 주장을 마이그레이션으로 검증한다.</p>
 *
 * <p>대기 학생({@code PENDING_PARENT_LINK})도 로그인은 성공한다. 막히는 건 로그인이 아니라
 * 그 뒤 기능이고, 판정은 {@code StudentActivationGuard} 가 한다.</p>
 */
@Service
public class StudentLoginService {

	private final PublicStudentIdRepository publicStudentIdRepository;
	private final RosterProfileWriterAdapter rosterAdapter;
	private final MemberAccountEmailReader emailReader;
	private final MemberLoginAdapter loginAdapter;

	public StudentLoginService(
		PublicStudentIdRepository publicStudentIdRepository,
		RosterProfileWriterAdapter rosterAdapter,
		MemberAccountEmailReader emailReader,
		MemberLoginAdapter loginAdapter
	) {
		this.publicStudentIdRepository = publicStudentIdRepository;
		this.rosterAdapter = rosterAdapter;
		this.emailReader = emailReader;
		this.loginAdapter = loginAdapter;
	}

	@Transactional
	public MemberAuthentication login(String rawPublicId, String rawPassword) {
		Optional<String> email = resolveEmail(rawPublicId);
		if (email.isEmpty()) {
			// 🔴 여기서 즉시 반환하면 응답 시간 차로 공개 ID 존재 여부가 샌다.
			loginAdapter.burnPasswordComparisonTime(rawPassword);
			throw invalidCredentials();
		}
		// 🔴 비밀번호 불일치 → INVALID_CREDENTIALS, 정지 계정 → ACCOUNT_NOT_ACTIVE 번역은
		//    어댑터가 한다. 남의 예외 타입을 여기서 잡으면 경계를 넘는다(G2).
		return loginAdapter.login(email.get(), rawPassword);
	}

	private Optional<String> resolveEmail(String rawPublicId) {
		String normalized = PublicStudentId.normalize(rawPublicId);
		if (normalized == null) {
			return Optional.empty();
		}
		Optional<UUID> studentProfileId =
			publicStudentIdRepository.findStudentByPublicId(normalized);
		if (studentProfileId.isEmpty()) {
			return Optional.empty();
		}
		return studentProfileId
			.flatMap(rosterAdapter::findStudentAccountId)
			.flatMap(emailReader::findEmail);
	}

	private MemberException invalidCredentials() {
		return new MemberException(
			MemberErrorCode.INVALID_CREDENTIALS, "public student id or password is not valid");
	}
}
