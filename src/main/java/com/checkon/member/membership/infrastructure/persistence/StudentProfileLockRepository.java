package com.checkon.member.membership.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 자녀 등록 트랜잭션의 행 잠금 전용.
 *
 * <p>🔴 이 잠금은 <b>경쟁의 최종 방어가 아니다.</b> 최종 보장은
 * {@code uq_parent_student_relationships_active_student}(V33:96-98) 다. 잠금은 같은 학생을
 * 노리는 요청들을 직렬화해서, 사전 조회가 친절한 409 를 만들 수 있게 해준다. 잠금을 빼도
 * 하나는 여전히 unique 위반으로 막힌다 — 다만 안내가 거칠어진다.</p>
 *
 * <p>🔴 {@code student_profiles} 는 RLS 대상이 아니다(V38 에 ENABLE 이 없다). 임의의
 * studentId 로 이 메서드를 부르는 공개 경로를 만들면 잠금이 열거 도구가 된다 —
 * 호출부는 공개 ID 해석을 통과한 값만 넘긴다.</p>
 */
@Repository
public class StudentProfileLockRepository {

	private static final String LOCK = """
		SELECT id FROM student_profiles WHERE id = ? FOR UPDATE
		""";

	private final JdbcTemplate jdbcTemplate;

	public StudentProfileLockRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<UUID> lock(UUID studentProfileId) {
		return jdbcTemplate.query(LOCK, rs -> rs.next()
			? Optional.ofNullable(rs.getObject(1, UUID.class))
			: Optional.<UUID>empty(), studentProfileId);
	}
}
