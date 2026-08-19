package com.checkon.counsel.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Local bookkeeping of counsel draft jobs, keyed by (teacher, Idempotency-Key).
 * The AI's own PG read model is the source of truth for draft bodies (§②-6 of
 * the contract) — this table only remembers which {@code job_id} a given
 * inquiry/idempotency key resolved to, and the job's last known phase.
 */
@Repository
public class CounselDraftJobRepository {

	private final JdbcClient jdbc;

	public CounselDraftJobRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Inserts a new job, or refreshes the known job id/phase if the same idempotency key was replayed. */
	public void upsert(NewJob value) {
		jdbc.sql("""
			INSERT INTO counsel_draft_jobs (
			 id, teacher_id, tenant_alias, inquiry_ref, student_ref, parent_ref, class_ref,
			 topic, idempotency_key, job_id, job_phase, ai_execution_id, requested_at, updated_at
			) VALUES (:id, :teacherId, :tenantAlias, :inquiryRef, :studentRef, :parentRef, :classRef,
			 :topic, :idempotencyKey, :jobId, :jobPhase, :aiExecutionId, :requestedAt, :updatedAt)
			ON CONFLICT (teacher_id, idempotency_key) DO UPDATE SET
			 job_id = EXCLUDED.job_id,
			 job_phase = EXCLUDED.job_phase,
			 ai_execution_id = EXCLUDED.ai_execution_id,
			 updated_at = EXCLUDED.updated_at
			""").params(Map.ofEntries(
				Map.entry("id", value.id()), Map.entry("teacherId", value.teacherId()),
				Map.entry("tenantAlias", value.tenantAlias()), Map.entry("inquiryRef", value.inquiryRef()),
				Map.entry("studentRef", value.studentRef()), Map.entry("parentRef", value.parentRef()),
				Map.entry("classRef", value.classRef()), Map.entry("topic", value.topic()),
				Map.entry("idempotencyKey", value.idempotencyKey()), Map.entry("jobId", value.jobId()),
				Map.entry("jobPhase", value.jobPhase()), Map.entry("aiExecutionId", nullable(value.aiExecutionId())),
				Map.entry("requestedAt", time(value.requestedAt())), Map.entry("updatedAt", time(value.updatedAt()))
			)).update();
	}

	/** Refreshes the last known phase after a GET, so local bookkeeping does not go stale between polls. */
	public void updateKnownPhase(UUID teacherId, String jobId, String jobPhase, Instant updatedAt) {
		jdbc.sql("""
			UPDATE counsel_draft_jobs SET job_phase = :jobPhase, updated_at = :updatedAt
			WHERE teacher_id = :teacherId AND job_id = :jobId
			""").param("jobPhase", jobPhase).param("updatedAt", time(updatedAt))
			.param("teacherId", teacherId).param("jobId", jobId).update();
	}

	public Optional<Job> findByTeacherAndIdempotencyKey(UUID teacherId, String idempotencyKey) {
		return jdbc.sql("""
			SELECT id, teacher_id, tenant_alias, inquiry_ref, student_ref, parent_ref, class_ref,
			 topic, idempotency_key, job_id, job_phase, ai_execution_id, requested_at, updated_at
			FROM counsel_draft_jobs
			WHERE teacher_id = :teacherId AND idempotency_key = :idempotencyKey
			""").param("teacherId", teacherId).param("idempotencyKey", idempotencyKey)
			.query(CounselDraftJobRepository::map).optional();
	}

	public Optional<Job> findByTeacherAndJobId(UUID teacherId, String jobId) {
		return jdbc.sql("""
			SELECT id, teacher_id, tenant_alias, inquiry_ref, student_ref, parent_ref, class_ref,
			 topic, idempotency_key, job_id, job_phase, ai_execution_id, requested_at, updated_at
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
			rs.getString("idempotency_key"), rs.getString("job_id"), rs.getString("job_phase"),
			rs.getString("ai_execution_id"), instant(rs, "requested_at"), instant(rs, "updated_at")
		);
	}

	private static Object nullable(Object value) { return value == null ? new SqlParameterValue(Types.OTHER, null) : value; }
	private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
	private static Instant instant(ResultSet rs, String column) throws SQLException { return rs.getObject(column, OffsetDateTime.class).toInstant(); }

	public record NewJob(
		UUID id, UUID teacherId, String tenantAlias, String inquiryRef, String studentRef, String parentRef,
		String classRef, String topic, String idempotencyKey, String jobId, String jobPhase,
		String aiExecutionId, Instant requestedAt, Instant updatedAt
	) {
	}

	public record Job(
		UUID id, UUID teacherId, String tenantAlias, String inquiryRef, String studentRef, String parentRef,
		String classRef, String topic, String idempotencyKey, String jobId, String jobPhase,
		String aiExecutionId, Instant requestedAt, Instant updatedAt
	) {
	}
}
