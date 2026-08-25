package com.checkon.member.auth.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.auth.application.MemberTeacherSummary;

/**
 * 세션에 실을 강사 목록.
 *
 * <p>🔴 조회는 반드시 RLS 컨텍스트가 설정된 트랜잭션 안에서 돈다. 컨텍스트 없이 읽으면
 * 예외가 아니라 <b>조용히 빈 배열</b>이 나온다 — PR2 §2-4 정책에 의존한다.</p>
 */
@Repository
public class MemberTeacherRepository {

	private static final String BY_STUDENT = """
		SELECT teacher.id, teacher.display_name
		FROM teacher_student_relationships link
		JOIN teacher_profiles teacher ON teacher.id = link.teacher_id
		WHERE link.student_id = ? AND link.status = 'ACTIVE'
		ORDER BY teacher.display_name
		""";

	private static final String BY_PARENT = """
		SELECT teacher.id, teacher.display_name
		FROM parent_teacher_relationships link
		JOIN teacher_profiles teacher ON teacher.id = link.teacher_id
		WHERE link.parent_id = ? AND link.status = 'ACTIVE'
		ORDER BY teacher.display_name
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberTeacherRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<MemberTeacherSummary> findForStudent(UUID studentProfileId) {
		return query(BY_STUDENT, studentProfileId);
	}

	public List<MemberTeacherSummary> findForParent(UUID parentProfileId) {
		return query(BY_PARENT, parentProfileId);
	}

	private List<MemberTeacherSummary> query(String sql, UUID id) {
		// 🔴 subject 는 null 이다. 원본이 없어서가 아니라 정책이 없어서다 —
		//    계약 member-api.yaml:1648-1650 "원본은 class_groups.subject(V13) 뿐이고
		//    학생·학부모는 class_groups 에 SELECT 정책이 없다."
		//    §6-4-2 범위 세션 변수로 언젠가 채울 수 있다. 지금 지어내지 않는다.
		return jdbcTemplate.query(sql,
			(rs, rowNum) -> new MemberTeacherSummary(
				rs.getObject(1, UUID.class), rs.getString(2), null),
			id);
	}
}
