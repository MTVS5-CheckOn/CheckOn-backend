package com.checkon.member.profile.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 학생 프로필에서 표시에 필요한 필드만 읽는다.
 *
 * <p>🔴 이름은 여기서 읽지 않는다 — {@code student_profiles.alias} 는 강사 로스터 이름이고
 * member API 의 이름은 {@code member_display_names}(V39)가 원본이다(설계 §1-4 ①).</p>
 *
 * <p>{@code student_profiles} 는 RLS 대상이 아니다(승우님 소유). 학생 소유 확인은 호출자가
 * subject.studentProfileId 로 조회하는 것으로 이미 격리된다.</p>
 */
@Repository
public class StudentProfileReader {

	private static final String FIND_GRADE = """
		SELECT grade FROM student_profiles WHERE id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public StudentProfileReader(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 🔴 null 이면 계약이 required 로 뒀지만 지어내지 않고 그대로 null 을 돌려준다. */
	public Optional<Integer> findGrade(UUID studentProfileId) {
		return jdbcTemplate.query(FIND_GRADE, rs -> rs.next()
			? Optional.ofNullable((Integer) rs.getObject(1))
			: Optional.<Integer>empty(), studentProfileId);
	}
}
