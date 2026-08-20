package com.checkon.counsel.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Local bookkeeping of counsel draft jobs, keyed by (teacher, Idempotency-Key).
 * {@code job_id} is minted by the backend at request time and returned to the
 * frontend immediately (202) — {@code ai_job_id} is the AI's own job id,
 * known only once the Kafka completion/failure event arrives, and is what
 * the REST GET/refine calls actually use.
 */
@Repository
public class CounselDraftJobRepository {

	private static final String COLUMNS = """
		id, teacher_id, tenant_alias, inquiry_ref, student_ref, parent_ref, class_ref,
		 topic, idempotency_key, job_id, ai_job_id, job_phase, ai_execution_id, request_hash,
		 requested_at, updated_at""";

	private final JdbcClient jdbc;

	public CounselDraftJobRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts a new job. Returns {@code false} if a row for this
	 * (teacher, idempotencyKey) already exists — callers must not have already
	 * minted a fresh job_id in that case, since "same key = same job_id" is now
	 * the backend's own guarantee, not the AI's; look the existing row up
	 * instead of inserting.
	 */
	public boolean insertIfAbsent(NewJob value) {
		int inserted = jdbc.sql("""
			INSERT INTO counsel_draft_jobs (
			 id, teacher_id, tenant_alias, inquiry_ref, student_ref, parent_ref, class_ref,
			 topic, idempotency_key, job_id, ai_job_id, job_phase, ai_execution_id, request_hash,
			 requested_at, updated_at
			) VALUES (:id, :teacherId, :tenantAlias, :inquiryRef, :studentRef, :parentRef, :classRef,
			 :topic, :idempotencyKey, :jobId, :aiJobId, :jobPhase, :aiExecutionId, :requestHash,
			 :requestedAt, :updatedAt)
			ON CONFLICT (teacher_id, idempotency_key) DO NOTHING
			""").params(Map.ofEntries(
				Map.entry("id", value.id()), Map.entry("teacherId", value.teacherId()),
				Map.entry("tenantAlias", value.tenantAlias()), Map.entry("inquiryRef", value.inquiryRef()),
				Map.entry("studentRef", value.studentRef()), Map.entry("parentRef", value.parentRef()),
				Map.entry("classRef", value.classRef()), Map.entry("topic", value.topic()),
				Map.entry("idempotencyKey", value.idempotencyKey()), Map.entry("jobId", value.jobId()),
				Map.entry("aiJobId", nullable(value.aiJobId())),
				Map.entry("jobPhase", value.jobPhase()), Map.entry("aiExecutionId", nullable(value.aiExecutionId())),
				Map.entry("requestHash", value.requestHash()),
				Map.entry("requestedAt", time(value.requestedAt())), Map.entry("updatedAt", time(value.updatedAt()))
			)).update();
		return inserted > 0;
	}

	/** Refreshes the last known phase after a GET, so local bookkeeping does not go stale between polls. */
	public void updateKnownPhase(UUID teacherId, String jobId, String jobPhase, Instant updatedAt) {
		jdbc.sql("""
			UPDATE counsel_draft_jobs SET job_phase = :jobPhase, updated_at = :updatedAt
			WHERE teacher_id = :teacherId AND job_id = :jobId
			""").param("jobPhase", jobPhase).param("updatedAt", time(updatedAt))
			.param("teacherId", teacherId).param("jobId", jobId).update();
	}

	/**
	 * Records the AI's real job id and terminal phase once the Kafka
	 * completion/failure event arrives. Returns {@code false} if no local job
	 * row exists for this (teacher, jobId).
	 */
	public boolean setAiOutcome(
		UUID teacherId, String jobId, String aiJobId, String jobPhase, String aiExecutionId, Instant updatedAt
	) {
		int updated = jdbc.sql("""
			UPDATE counsel_draft_jobs
			SET ai_job_id = COALESCE(:aiJobId, ai_job_id), job_phase = :jobPhase,
			 ai_execution_id = COALESCE(:aiExecutionId, ai_execution_id), updated_at = :updatedAt
			WHERE teacher_id = :teacherId AND job_id = :jobId
			""").param("aiJobId", nullable(aiJobId)).param("jobPhase", jobPhase)
			.param("aiExecutionId", nullable(aiExecutionId)).param("updatedAt", time(updatedAt))
			.param("teacherId", teacherId).param("jobId", jobId).update();
		return updated > 0;
	}

	/**
	 * Records the text the teacher actually sent through their own channel
	 * (contract appendix §7). Returns {@code false} if no local job row exists
	 * for this (teacher, jobId).
	 */
	public boolean markSent(UUID teacherId, String jobId, String sentText, Instant sentAt) {
		int updated = jdbc.sql("""
			UPDATE counsel_draft_jobs SET sent_text = :sentText, sent_at = :sentAt
			WHERE teacher_id = :teacherId AND job_id = :jobId
			""").param("sentText", sentText).param("sentAt", time(sentAt))
			.param("teacherId", teacherId).param("jobId", jobId).update();
		return updated > 0;
	}

	/**
	 * Jobs whose last known phase is not terminal ({@code succeeded|failed|cancelled}).
	 * Used by the polling job to refresh local bookkeeping — a GET never advances
	 * the job itself (§0-3 of the counsel contract), so this only keeps the
	 * locally stored phase from going stale, it does not unstick a queued job.
	 */
	public List<Job> findNonTerminalByTeacher(UUID teacherId) {
		return jdbc.sql("SELECT " + COLUMNS + """

			FROM counsel_draft_jobs
			WHERE teacher_id = :teacherId AND job_phase NOT IN ('succeeded', 'failed', 'cancelled')
			""").param("teacherId", teacherId)
			.query(CounselDraftJobRepository::map).list();
	}

	public Optional<Job> findByTeacherAndIdempotencyKey(UUID teacherId, String idempotencyKey) {
		return jdbc.sql("SELECT " + COLUMNS + """

			FROM counsel_draft_jobs
			WHERE teacher_id = :teacherId AND idempotency_key = :idempotencyKey
			""").param("teacherId", teacherId).param("idempotencyKey", idempotencyKey)
			.query(CounselDraftJobRepository::map).optional();
	}

	public Optional<Job> findByTeacherAndJobId(UUID teacherId, String jobId) {
		return jdbc.sql("SELECT " + COLUMNS + """

			FROM counsel_draft_jobs
			WHERE teacher_id = :teacherId AND job_id = :jobId
			""").param("teacherId", teacherId).param("jobId", jobId)
			.query(CounselDraftJobRepository::map).optional();
	}

	private static Job map(ResultSet rs, int row) throws SQLException {
		return new Job(
			rs.getObject("id", UUID.class), rs.getObject("teacher_id", UUID.class),
			rs.getString("tenant_alias"), rs.getString("inquiry_ref"), rs.getString("student_ref"),
			rs.getString("parent_ref"), rs.getString("class_ref"), rs.getString("topic"),
			rs.getString("idempotency_key"), rs.getString("job_id"), rs.getString("ai_job_id"), rs.getString("job_phase"),
			rs.getString("ai_execution_id"), rs.getString("request_hash"), instant(rs, "requested_at"), instant(rs, "updated_at")
		);
	}

	private static Object nullable(Object value) { return value == null ? new SqlParameterValue(Types.OTHER, null) : value; }
	private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
	private static Instant instant(ResultSet rs, String column) throws SQLException { return rs.getObject(column, OffsetDateTime.class).toInstant(); }

	public record NewJob(
		UUID id, UUID teacherId, String tenantAlias, String inquiryRef, String studentRef, String parentRef,
		String classRef, String topic, String idempotencyKey, String jobId, String aiJobId, String jobPhase,
		String aiExecutionId, String requestHash, Instant requestedAt, Instant updatedAt
	) {
	}

	public record Job(
		UUID id, UUID teacherId, String tenantAlias, String inquiryRef, String studentRef, String parentRef,
		String classRef, String topic, String idempotencyKey, String jobId, String aiJobId, String jobPhase,
		String aiExecutionId, String requestHash, Instant requestedAt, Instant updatedAt
	) {
	}
}
