package com.checkon.member.auth.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.checkon.member.auth.application.ActivationCommandPort;
import com.checkon.member.auth.application.ActivationTransition;
import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;

/**
 * {@code member_student_activation} 전이 구현.
 *
 * <p>🔴 0행을 그냥 넘기지 않는다. UPDATE 가 0행인 경우는 셋인데 성격이 전혀 다르다 —
 * ① 이미 {@code ACTIVE}(정상) ② RLS 범위를 안 열었다(장애) ③ 행 자체가 없다(가입이 깨졌다).
 * 그래서 <b>먼저 현재 status 를 읽어 분기</b>하고, 어느 쪽으로도 설명되지 않는 0행만 예외로 올린다.
 * 이 검사가 없으면 자녀 등록이 201 을 돌려주면서 학생은 영영 대기 상태로 남는다.</p>
 */
@Component
public class ActivationCommandAdapter implements ActivationCommandPort {

	private static final String ACTIVATE = """
		UPDATE member_student_activation
		SET status = ?, activated_at = ?, updated_at = ?
		WHERE student_id = ? AND status = ?
		""";

	private final MemberActivationRepository activationRepository;
	private final JdbcTemplate jdbcTemplate;

	public ActivationCommandAdapter(
		MemberActivationRepository activationRepository,
		JdbcTemplate jdbcTemplate
	) {
		this.activationRepository = activationRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public ActivationTransition activate(UUID studentProfileId, Instant now) {
		// 🔴 이 조회 자체가 범위(scope)를 요구한다. 비어 있으면 「행이 없다」가 아니라
		//    「내 범위에서 안 보인다」일 수 있어, 아래에서 둘을 구분하지 않고 함께 막는다.
		Optional<MemberActivationStatus> current =
			activationRepository.findStatus(studentProfileId);
		if (current.isEmpty()) {
			throw new MemberException(MemberErrorCode.INTERNAL,
				"activation row is not visible for student " + studentProfileId);
		}
		if (current.get() == MemberActivationStatus.ACTIVE) {
			return ActivationTransition.ALREADY_ACTIVE;
		}

		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		int affected = jdbcTemplate.update(ACTIVATE,
			MemberActivationStatus.ACTIVE.name(), at, at, studentProfileId, current.get().name());
		if (affected == 0) {
			// 위에서 status 를 읽어 「이미 ACTIVE」를 걸러냈으므로 여기 0행은 설명되지 않는다.
			// UPDATE 정책(V39 _parent_scope_update)이 막았거나 다른 트랜잭션이 상태를 바꿨다.
			throw new MemberException(MemberErrorCode.INTERNAL,
				"activation update affected no row for student " + studentProfileId);
		}
		return ActivationTransition.ACTIVATED;
	}
}
