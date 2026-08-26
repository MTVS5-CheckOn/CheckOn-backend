package com.checkon.member.learning.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.learning.domain.MemberAttempt;
import com.checkon.member.learning.domain.MemberAttemptStatus;

/**
 * {@code member_attempts} 저장·조회.
 *
 * <p>🔴 RLS 정책은 학생 self select/insert/update, 학부모·강사 scope select 다(V40:210-247).
 * 학생 컨텍스트에서 self 조회·수정만 이 리포지토리를 통해 이루어진다 — 학부모·강사 화면 조회는
 * 별도 리포지토리가 담당한다(S1 범위 밖).</p>
 *
 * <p>🔴 {@code uq_member_attempts_open}(V40:100-101, partial unique on IN_PROGRESS) 이 이중
 * 시작을 물리적으로 막는다. {@link #insert} 가 23505 로 실패하면 호출자가 {@link #findOpen} 을
 * 재조회해 재개 응답을 낸다 — 재시도는 1회 상한(설계 정본 §12).</p>
 */
@Repository
public class MemberAttemptRepository {

	private static final String INSERT = """
		INSERT INTO member_attempts
			(id, student_id, assignment_id, teacher_id, status, version, snapshot_hash,
			 item_count, active_elapsed_sec, last_client_sequence, started_at,
			 last_progress_at, submitted_at, scored_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private static final String COLUMNS =
		"id, student_id, assignment_id, teacher_id, status, version, snapshot_hash, "
			+ "item_count, active_elapsed_sec, last_client_sequence, started_at, "
			+ "last_progress_at, submitted_at, scored_at";

	private static final String FIND_OPEN = """
		SELECT %s FROM member_attempts
		WHERE student_id = ? AND assignment_id = ? AND status = 'IN_PROGRESS'
		""".formatted(COLUMNS);

	private static final String FIND_BY_ID = """
		SELECT %s FROM member_attempts WHERE id = ?
		""".formatted(COLUMNS);

	private final JdbcTemplate jdbcTemplate;

	public MemberAttemptRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 🔴 {@code uq_member_attempts_open} 위반은 그대로 던진다 — 서비스가 200 재개로 수렴시킨다. */
	public void insert(MemberAttempt attempt) {
		jdbcTemplate.update(INSERT,
			attempt.id(),
			attempt.studentId(),
			attempt.assignmentId(),
			attempt.teacherId(),
			attempt.status().name(),
			attempt.version(),
			attempt.snapshotHash(),
			attempt.itemCount(),
			attempt.activeElapsedSec(),
			attempt.lastClientSequence(),
			offset(attempt.startedAt()),
			offset(attempt.lastProgressAt()),
			offset(attempt.submittedAt()),
			offset(attempt.scoredAt()));
	}

	public Optional<MemberAttempt> findOpen(UUID studentId, UUID assignmentId) {
		return jdbcTemplate.query(FIND_OPEN, rs -> rs.next()
			? Optional.of(map(rs))
			: Optional.<MemberAttempt>empty(), studentId, assignmentId);
	}

	public Optional<MemberAttempt> findById(UUID attemptId) {
		return jdbcTemplate.query(FIND_BY_ID, rs -> rs.next()
			? Optional.of(map(rs))
			: Optional.<MemberAttempt>empty(), attemptId);
	}

	private static MemberAttempt map(ResultSet rs) throws java.sql.SQLException {
		return new MemberAttempt(
			rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class),
			rs.getObject(3, UUID.class),
			rs.getObject(4, UUID.class),
			MemberAttemptStatus.valueOf(rs.getString(5)),
			rs.getInt(6),
			rs.getString(7),
			rs.getInt(8),
			rs.getInt(9),
			(Integer) rs.getObject(10),
			instant(rs.getObject(11, OffsetDateTime.class)),
			instant(rs.getObject(12, OffsetDateTime.class)),
			instant(rs.getObject(13, OffsetDateTime.class)),
			instant(rs.getObject(14, OffsetDateTime.class)));
	}

	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime offset(Instant value) {
		return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}
}
