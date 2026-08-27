package com.checkon.member.integration.roster;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.sql.ResultSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import com.checkon.member.integration.roster.dto.ChildLinkView;
import com.checkon.member.integration.roster.dto.StudentIdentity;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;
import com.checkon.member.common.persistence.RelationshipStatus;

/**
 * roster·member 조회를 member 전용 record 로 바꿔 돌려준다.
 *
 * <p>🔴 왜 엔티티가 아니라 네이티브 SQL 인가 — {@code RosterProfileWriterAdapter} 와 같은 이유다.
 * roster 엔티티에는 필요한 접근자가 없고 그 파일은 팀원 소유라 고칠 수 없다. member 안에
 * 같은 테이블의 두 번째 매핑을 만들면 flush 순서로 깨진다.</p>
 *
 * <p>🔴 모든 조회는 호출자가 RLS 컨텍스트를 연 트랜잭션 안에서 돈다. 컨텍스트가 없으면 예외가
 * 아니라 <b>빈 결과</b>다(설계 §6-4-3).</p>
 */
@Component
public class RosterRelationshipAdapter implements RosterRelationshipPort {

	private static final String FIND_STUDENT_BY_PUBLIC_ID = """
		SELECT student.id, student.account_id, student.alias, student.grade
		FROM member_student_public_ids public_id
		JOIN student_profiles student ON student.id = public_id.student_id
		WHERE public_id.public_id = ?
		""";

	private static final String FIND_ACTIVE_CHILDREN = """
		SELECT student.id, student.account_id, student.alias, student.grade,
		       public_id.public_id, link.started_at
		FROM parent_student_relationships link
		JOIN student_profiles student ON student.id = link.student_id
		LEFT JOIN member_student_public_ids public_id ON public_id.student_id = student.id
		WHERE link.parent_id = ? AND link.status = ?
		ORDER BY link.started_at
		""";

	private static final String FIND_TEACHER = """
		SELECT id, display_name FROM teacher_profiles WHERE id = ?
		""";

	private static final String EXISTS_ACTIVE_PARENT_LINK = """
		SELECT 1 FROM parent_student_relationships
		WHERE student_id = ? AND status = ?
		LIMIT 1
		""";

	// 🔴 학부모 컨텍스트 + scope_student_id 로 열린 뒤에만 행이 보인다(V40 · MB-36).
	//    student_id 조건은 방어가 아니라 인덱스 힌트다 — 정책이 이미 격리한다.
	private static final String FIND_TEACHERS_OF_CHILD = """
		SELECT teacher.id, teacher.display_name
		FROM teacher_student_relationships link
		JOIN teacher_profiles teacher ON teacher.id = link.teacher_id
		WHERE link.student_id = ? AND link.status = ?
		ORDER BY link.started_at
		""";

	/**
	 * 🔴 {@code teacher_profiles} 를 조인하지 않는다 — 필터 판정에 필요한 것은 id 집합뿐이고,
	 * 조인하면 강사 프로필 정책까지 걸려 판정이 흐려진다.
	 */
	private static final String FIND_TEACHER_IDS_OF_PARENT = """
		SELECT teacher_id FROM parent_teacher_relationships
		WHERE parent_id = ? AND status = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public RosterRelationshipAdapter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<StudentIdentity> findStudentByPublicId(String normalizedPublicId) {
		if (normalizedPublicId == null) {
			return Optional.empty();
		}
		return jdbcTemplate.query(FIND_STUDENT_BY_PUBLIC_ID, rs -> rs.next()
			? Optional.of(new StudentIdentity(
				rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
				rs.getString(3), grade(rs.getObject(4))))
			: Optional.<StudentIdentity>empty(), normalizedPublicId);
	}

	@Override
	public List<ChildLinkView> findActiveChildren(UUID parentProfileId) {
		// 🔴 WHERE 절의 parent_id 는 방어가 아니다 — 정책이 이미 격리한다.
		//    idx_parent_student_relationships_parent_status 를 타게 하려고 넣는다.
		return jdbcTemplate.query(FIND_ACTIVE_CHILDREN, (rs, rowNum) -> new ChildLinkView(
			rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
			rs.getString(3), grade(rs.getObject(4)), rs.getString(5),
			instant(rs.getObject(6, OffsetDateTime.class))),
			parentProfileId, RelationshipStatus.ACTIVE.name());
	}

	@Override
	public Optional<TeacherSummaryView> findTeacherSummary(UUID teacherId) {
		return jdbcTemplate.query(FIND_TEACHER, rs -> rs.next()
			? Optional.of(TeacherSummaryView.of(rs.getObject(1, UUID.class), rs.getString(2)))
			: Optional.<TeacherSummaryView>empty(), teacherId);
	}

	@Override
	public List<TeacherSummaryView> findTeachersOfChild(UUID studentProfileId) {
		return jdbcTemplate.query(FIND_TEACHERS_OF_CHILD, (rs, rowNum) ->
			TeacherSummaryView.of(rs.getObject(1, UUID.class), rs.getString(2)),
			studentProfileId, RelationshipStatus.ACTIVE.name());
	}

	@Override
	public List<UUID> findActiveTeacherIdsOfParent(UUID parentProfileId) {
		// 🔴 WHERE 절의 parent_id 는 방어가 아니다 — V38 정책이 이미 격리한다.
		//    인덱스를 타게 하려고 넣는다(findActiveChildren 과 같은 이유).
		return jdbcTemplate.queryForList(FIND_TEACHER_IDS_OF_PARENT, UUID.class,
			parentProfileId, RelationshipStatus.ACTIVE.name());
	}

	@Override
	public boolean existsActiveParentLink(UUID studentProfileId) {
		// 람다만 두면 ResultSetExtractor 와 RowCallbackHandler 중 무엇인지 모호하다. 명시한다.
		ResultSetExtractor<Boolean> hasRow = ResultSet::next;
		return Boolean.TRUE.equals(jdbcTemplate.query(EXISTS_ACTIVE_PARENT_LINK, hasRow,
			studentProfileId, RelationshipStatus.ACTIVE.name()));
	}

	/** {@code grade} 는 SMALLINT 라 드라이버가 Short 로 준다. 없으면 {@code null} 을 유지한다. */
	private Integer grade(Object value) {
		return value instanceof Number number ? number.intValue() : null;
	}

	private java.time.Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
