package com.checkon.roster.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.roster.domain.ClassGroupStatus;

/**
 * 클래스 화면을 위한 읽기 전용 projection이다.
 *
 * <p>Aggregate 객체 그래프를 만들지 않고 클래스 메타데이터와 활성 소속 수를
 * 한 쿼리에서 읽는다. RLS가 최종 방어를 담당하더라도 모든 SQL에 인증
 * principal에서 얻은 teacherId 조건을 명시한다.</p>
 */
@Repository
public class ClassGroupQueryRepository {
	private final JdbcTemplate jdbcTemplate;

	public ClassGroupQueryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public PageData findActive(UUID teacherId, int page, int size) {
		long totalElements = jdbcTemplate.queryForObject("""
			SELECT count(*)
			FROM class_groups class_group
			WHERE class_group.teacher_id = ?
			  AND class_group.status = 'ACTIVE'
			""", Long.class, teacherId);
		long offset = (long) page * size;
		List<Row> content = jdbcTemplate.query("""
			SELECT
			    class_group.id,
			    class_group.name,
			    class_group.subject,
			    class_group.memo,
			    class_group.status,
			    class_group.created_at,
			    class_group.updated_at,
			    count(enrollment.id) AS active_student_count
			FROM class_groups class_group
			LEFT JOIN class_enrollments enrollment
			  ON enrollment.class_group_id = class_group.id
			 AND enrollment.teacher_id = class_group.teacher_id
			 AND enrollment.status = 'ACTIVE'
			WHERE class_group.teacher_id = ?
			  AND class_group.status = 'ACTIVE'
			GROUP BY class_group.id
			ORDER BY class_group.created_at DESC, class_group.id DESC
			LIMIT ? OFFSET ?
			""", ClassGroupQueryRepository::mapRow, teacherId, size, offset);
		return new PageData(content, totalElements);
	}

	public Optional<Row> findById(UUID teacherId, UUID classGroupId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject("""
				SELECT
				    class_group.id,
				    class_group.name,
				    class_group.subject,
				    class_group.memo,
				    class_group.status,
				    class_group.created_at,
				    class_group.updated_at,
				    count(enrollment.id) AS active_student_count
				FROM class_groups class_group
				LEFT JOIN class_enrollments enrollment
				  ON enrollment.class_group_id = class_group.id
				 AND enrollment.teacher_id = class_group.teacher_id
				 AND enrollment.status = 'ACTIVE'
				WHERE class_group.teacher_id = ?
				  AND class_group.id = ?
				GROUP BY class_group.id
				""", ClassGroupQueryRepository::mapRow, teacherId, classGroupId));
		}
		catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	private static Row mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new Row(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("name"),
			resultSet.getString("subject"),
			resultSet.getString("memo"),
			ClassGroupStatus.valueOf(resultSet.getString("status")),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
			resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
			resultSet.getLong("active_student_count")
		);
	}

	public record PageData(List<Row> content, long totalElements) {
		public PageData {
			content = List.copyOf(content);
		}
	}

	public record Row(
		UUID classId,
		String name,
		String subject,
		String memo,
		ClassGroupStatus status,
		java.time.Instant createdAt,
		java.time.Instant updatedAt,
		long activeStudentCount
	) {
	}
}
