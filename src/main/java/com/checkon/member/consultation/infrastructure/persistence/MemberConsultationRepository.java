package com.checkon.member.consultation.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.consultation.domain.ConsultationAiStatus;
import com.checkon.member.consultation.domain.ConsultationAuthorRole;
import com.checkon.member.consultation.domain.ConsultationContextType;
import com.checkon.member.consultation.domain.ConsultationMessageRecord;
import com.checkon.member.consultation.domain.ConsultationRecord;
import com.checkon.member.consultation.domain.ConsultationStatus;

@Repository
public class MemberConsultationRepository {

	private static final String COLUMNS = "id, parent_id, student_id, teacher_id, content, "
		+ "masked_content, status, ai_status, context_type, context_id, ai_job_id, "
		+ "created_at, updated_at, answered_at, cancelled_at";

	private static final String INSERT = """
		INSERT INTO member_consultations
			(parent_id, student_id, teacher_id, content, masked_content, status, ai_status,
			 context_type, context_id, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, 'SUBMITTED', 'NOT_REQUESTED', ?, ?, ?, ?)
		RETURNING id
		""";

	private static final String FIND_BY_ID = """
		SELECT %s FROM member_consultations WHERE id = ? AND student_id = ?
		""".formatted(COLUMNS);

	private static final String FIND_PAGE = """
		SELECT %s FROM member_consultations
		WHERE parent_id = ? AND student_id = ?
		  AND (?::timestamptz IS NULL
		       OR (created_at, id) < (?::timestamptz, ?::uuid))
		ORDER BY created_at DESC, id DESC
		LIMIT ?
		""".formatted(COLUMNS);

	private static final String FIND_MESSAGES = """
		SELECT id, consultation_id, author_role, content, published_at
		FROM member_consultation_messages
		WHERE consultation_id = ? AND student_id = ?
		ORDER BY published_at ASC, id ASC
		""";

	private static final String RECORD_CONTEXT = """
		SELECT count(*) FROM member_learning_sessions WHERE id = ? AND student_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberConsultationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public UUID insert(ConsultationRecord row) {
		return jdbcTemplate.queryForObject(INSERT, UUID.class,
			row.parentId(), row.studentId(), row.teacherId(), row.content(), row.maskedContent(),
			name(row.contextType()), row.contextId(),
			offset(row.createdAt()), offset(row.updatedAt()));
	}

	public Optional<ConsultationRecord> findById(UUID id, UUID studentId) {
		return jdbcTemplate.query(FIND_BY_ID, rs -> rs.next()
			? Optional.of(map(rs))
			: Optional.<ConsultationRecord>empty(), id, studentId);
	}

	public List<ConsultationRecord> findPage(
		UUID parentId, UUID studentId, Instant cursorAt, UUID cursorId, int limit
	) {
		OffsetDateTime cursor = offset(cursorAt);
		return jdbcTemplate.query(FIND_PAGE, (rs, rowNum) -> map(rs),
			parentId, studentId, cursor, cursor, cursorId, limit);
	}

	public List<ConsultationMessageRecord> findMessages(UUID consultationId, UUID studentId) {
		return jdbcTemplate.query(FIND_MESSAGES, (rs, rowNum) -> new ConsultationMessageRecord(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			ConsultationAuthorRole.valueOf(rs.getString(3)),
			rs.getString(4),
			rs.getObject(5, OffsetDateTime.class).toInstant()
		), consultationId, studentId);
	}

	public boolean contextExists(ConsultationContextType type, UUID id, UUID studentId) {
		if (type != ConsultationContextType.RECORD) {
			return false;
		}
		Integer count = jdbcTemplate.queryForObject(RECORD_CONTEXT, Integer.class, id, studentId);
		return count != null && count > 0;
	}

	private static ConsultationRecord map(ResultSet rs) throws java.sql.SQLException {
		String contextType = rs.getString(9);
		return new ConsultationRecord(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getObject(3, UUID.class),
			rs.getObject(4, UUID.class),
			rs.getString(5),
			rs.getString(6),
			ConsultationStatus.valueOf(rs.getString(7)),
			ConsultationAiStatus.valueOf(rs.getString(8)),
			contextType == null ? null : ConsultationContextType.valueOf(contextType),
			(UUID) rs.getObject(10),
			rs.getString(11),
			rs.getObject(12, OffsetDateTime.class).toInstant(),
			rs.getObject(13, OffsetDateTime.class).toInstant(),
			instant(rs.getObject(14, OffsetDateTime.class)),
			instant(rs.getObject(15, OffsetDateTime.class))
		);
	}

	private static String name(ConsultationContextType value) {
		return value == null ? null : value.name();
	}

	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime offset(Instant value) {
		return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}
}
