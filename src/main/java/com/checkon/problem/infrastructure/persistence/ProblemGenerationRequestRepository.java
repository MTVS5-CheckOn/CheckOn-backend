package com.checkon.problem.infrastructure.persistence;

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

import com.checkon.problem.application.ProblemGenerationRequestView;
import com.checkon.problem.domain.ProblemGenerationStatus;
import com.checkon.problem.domain.ProblemTargetKind;

@Repository
public class ProblemGenerationRequestRepository {
	private static final String SELECT_COLUMNS = """
		SELECT id, target_kind, status, ai_job_id, ai_execution_id, ai_set_id,
		       ai_result_status, error_code, result_payload::text AS result_payload,
		       versions_payload::text AS versions_payload, requested_at,
		       dispatched_at, completed_at, snapshot_hash, tenant_alias
		FROM problem_generation_requests
		""";

	private final JdbcClient jdbcClient;
	public ProblemGenerationRequestRepository(JdbcClient jdbcClient) { this.jdbcClient = jdbcClient; }

	public boolean insert(NewRequest request) {
		return jdbcClient.sql("""
			INSERT INTO problem_generation_requests (
			    id, teacher_id, tenant_alias, target_kind, student_id, diagnosis_id,
			    class_group_id, target_ref, client_idempotency_key,
			    ai_idempotency_key, snapshot_hash, request_payload, status,
			    requested_at, updated_at
			) VALUES (
			    :id, :teacherId, :tenantAlias, :targetKind, :studentId, :diagnosisId,
			    :classGroupId, :targetRef, :clientKey,
			    :aiKey, :snapshotHash, CAST(:requestPayload AS jsonb), 'QUEUED',
			    :requestedAt, :requestedAt
			) ON CONFLICT DO NOTHING
			""").params(Map.ofEntries(
			Map.entry("id", request.id()), Map.entry("teacherId", request.teacherId()),
			Map.entry("tenantAlias", request.tenantAlias()), Map.entry("targetKind", request.targetKind().name()),
			Map.entry("studentId", nullable(request.studentId())), Map.entry("classGroupId", nullable(request.classGroupId())),
			Map.entry("diagnosisId", nullable(request.diagnosisId())),
			Map.entry("targetRef", request.targetRef()), Map.entry("clientKey", nullable(request.clientIdempotencyKey())),
			Map.entry("aiKey", request.aiIdempotencyKey()), Map.entry("snapshotHash", request.snapshotHash()),
			Map.entry("requestPayload", request.requestPayload()), Map.entry("requestedAt", databaseTime(request.requestedAt()))
		)).update() == 1;
	}

	public Optional<ProblemGenerationRequestView> findByIdAndTeacherId(UUID id, UUID teacherId) {
		return jdbcClient.sql(SELECT_COLUMNS + " WHERE id = :id AND teacher_id = :teacherId")
			.param("id", id).param("teacherId", teacherId)
			.query(ProblemGenerationRequestRepository::mapView).optional();
	}

	public Optional<LockedRequest> findByIdAndTeacherIdForUpdate(UUID id, UUID teacherId) {
		return jdbcClient.sql("""
			SELECT id, teacher_id, tenant_alias, status, ai_job_id,
			       ai_execution_id, ai_set_id, ai_result_status
			FROM problem_generation_requests
			WHERE id = :id AND teacher_id = :teacherId FOR UPDATE
			""").param("id", id).param("teacherId", teacherId)
			.query((rs, row) -> new LockedRequest(
				rs.getObject("id", UUID.class), rs.getObject("teacher_id", UUID.class),
				rs.getString("tenant_alias"), ProblemGenerationStatus.valueOf(rs.getString("status")),
				rs.getString("ai_job_id"), rs.getString("ai_execution_id"),
				rs.getString("ai_set_id"), rs.getString("ai_result_status"))).optional();
	}

	public Optional<IdempotentRequest> findByClientKey(UUID teacherId, String clientKey) {
		if (clientKey == null) return Optional.empty();
		return jdbcClient.sql("""
			SELECT id, snapshot_hash FROM problem_generation_requests
			WHERE teacher_id = :teacherId AND client_idempotency_key = :clientKey
			""").param("teacherId", teacherId).param("clientKey", clientKey)
			.query((rs, row) -> new IdempotentRequest(rs.getObject("id", UUID.class), rs.getString("snapshot_hash"))).optional();
	}

	public void markDispatched(UUID id, UUID teacherId, Instant dispatchedAt) {
		jdbcClient.sql("""
			UPDATE problem_generation_requests
			SET status = 'DISPATCHED', dispatched_at = COALESCE(dispatched_at, :now),
			    error_code = NULL, updated_at = :now
			WHERE id = :id AND teacher_id = :teacherId AND status = 'QUEUED'
			""").param("id", id).param("teacherId", teacherId)
			.param("now", databaseTime(dispatchedAt)).update();
	}

	public void markDeliveryFailed(UUID id, UUID teacherId, Instant failedAt) {
		jdbcClient.sql("""
			UPDATE problem_generation_requests
			SET status = 'DELIVERY_FAILED', error_code = 'KAFKA_DELIVERY_FAILED', updated_at = :now
			WHERE id = :id AND teacher_id = :teacherId
			  AND status IN ('QUEUED', 'DISPATCHED', 'DELIVERY_FAILED')
			""").param("id", id).param("teacherId", teacherId)
			.param("now", databaseTime(failedAt)).update();
	}

	public void applyResult(ResultUpdate update) {
		jdbcClient.sql("""
			UPDATE problem_generation_requests
			SET status = :status,
			    ai_job_id = COALESCE(:jobId, ai_job_id),
			    ai_execution_id = COALESCE(:executionId, ai_execution_id),
			    ai_set_id = COALESCE(:setId, ai_set_id),
			    ai_result_status = COALESCE(:resultStatus, ai_result_status),
			    error_code = :errorCode,
			    result_payload = COALESCE(CAST(:resultPayload AS jsonb), result_payload),
			    versions_payload = COALESCE(CAST(:versionsPayload AS jsonb), versions_payload),
			    completed_at = :completedAt,
			    updated_at = :updatedAt
			WHERE id = :id AND teacher_id = :teacherId
			""").params(Map.ofEntries(
			Map.entry("id", update.id()), Map.entry("teacherId", update.teacherId()), Map.entry("status", update.status().name()),
			Map.entry("jobId", nullable(update.jobId())), Map.entry("executionId", nullable(update.executionId())),
			Map.entry("setId", nullable(update.setId())), Map.entry("resultStatus", nullable(update.resultStatus())),
			Map.entry("errorCode", nullable(update.errorCode())), Map.entry("resultPayload", nullable(update.resultPayload())),
			Map.entry("versionsPayload", nullable(update.versionsPayload())), Map.entry("completedAt", nullableTime(update.completedAt())),
			Map.entry("updatedAt", databaseTime(update.updatedAt()))
		)).update();
	}

	public void updateAggregatedStatus(UUID id, UUID teacherId, ProblemGenerationStatus status, String errorCode, Instant now) {
		jdbcClient.sql("""
			UPDATE problem_generation_requests SET status=:status,error_code=:errorCode,
			completed_at=:completedAt,updated_at=:now WHERE id=:id AND teacher_id=:teacherId
			""").param("status",status.name()).param("errorCode",nullable(errorCode))
			.param("completedAt", status.terminal() ? databaseTime(now) : nullable(null))
			.param("now",databaseTime(now)).param("id",id).param("teacherId",teacherId).update();
	}

	private static Object nullable(Object value) { return value == null ? new SqlParameterValue(Types.OTHER, null) : value; }
	private static Object nullableTime(Instant value) { return value == null ? nullable(null) : databaseTime(value); }
	private static OffsetDateTime databaseTime(Instant value) { return value.atOffset(ZoneOffset.UTC); }
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
	private static ProblemGenerationRequestView mapView(ResultSet rs, int row) throws SQLException {
		return new ProblemGenerationRequestView(rs.getObject("id", UUID.class),
			ProblemTargetKind.valueOf(rs.getString("target_kind")), ProblemGenerationStatus.valueOf(rs.getString("status")),
			rs.getString("ai_job_id"), rs.getString("ai_execution_id"), rs.getString("ai_set_id"),
			rs.getString("ai_result_status"), rs.getString("error_code"), rs.getString("result_payload"),
			rs.getString("versions_payload"), instant(rs, "requested_at"), instant(rs, "dispatched_at"), instant(rs, "completed_at"));
	}

	public record NewRequest(UUID id, UUID teacherId, String tenantAlias, ProblemTargetKind targetKind,
		UUID studentId, UUID classGroupId, UUID diagnosisId, String targetRef, String clientIdempotencyKey,
		String aiIdempotencyKey, String snapshotHash, String requestPayload, Instant requestedAt) { }
	public record IdempotentRequest(UUID id, String snapshotHash) { }
	public record LockedRequest(UUID id, UUID teacherId, String tenantAlias, ProblemGenerationStatus status,
		String jobId, String executionId, String setId, String resultStatus) { }
	public record ResultUpdate(UUID id, UUID teacherId, ProblemGenerationStatus status, String jobId,
		String executionId, String setId, String resultStatus, String errorCode, String resultPayload,
		String versionsPayload, Instant completedAt, Instant updatedAt) { }
}
