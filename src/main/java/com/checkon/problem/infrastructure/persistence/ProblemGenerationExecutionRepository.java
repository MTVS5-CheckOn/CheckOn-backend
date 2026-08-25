package com.checkon.problem.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.checkon.problem.domain.ProblemGenerationExecutionStatus;

@Repository
public class ProblemGenerationExecutionRepository {
	private final JdbcClient jdbc;
	public ProblemGenerationExecutionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

	public void insert(NewExecution value) {
		jdbc.sql("""
			INSERT INTO problem_generation_executions (
			 id, teacher_id, problem_request_id, request_target_id, target_index, status,
			 ai_idempotency_key, request_snapshot_hash, request_snapshot, created_at, updated_at
			) VALUES (:id,:teacherId,:requestId,:targetId,:targetIndex,'QUEUED',
			 :aiKey,:snapshotHash,CAST(:snapshot AS jsonb),:now,:now)
			""").params(Map.of("id", value.id(), "teacherId", value.teacherId(), "requestId", value.requestId(),
			"targetId", value.targetId(), "targetIndex", value.targetIndex(), "aiKey", value.aiIdempotencyKey(),
			"snapshotHash", value.snapshotHash(), "snapshot", value.snapshot(), "now", value.createdAt().atOffset(ZoneOffset.UTC)))
			.update();
	}

	public void insertRejected(NewExecution value, String errorCode) {
		jdbc.sql("""
			INSERT INTO problem_generation_executions (
			 id,teacher_id,problem_request_id,request_target_id,target_index,status,
			 ai_idempotency_key,request_snapshot_hash,request_snapshot,error_code,completed_at,created_at,updated_at
			) VALUES (:id,:teacherId,:requestId,:targetId,:targetIndex,'REJECTED_INSUFFICIENT',
			 :aiKey,:snapshotHash,CAST(:snapshot AS jsonb),:errorCode,:now,:now,:now)
			""").params(Map.ofEntries(Map.entry("id",value.id()),Map.entry("teacherId",value.teacherId()),
			Map.entry("requestId",value.requestId()),Map.entry("targetId",value.targetId()),Map.entry("targetIndex",value.targetIndex()),
			Map.entry("aiKey",value.aiIdempotencyKey()),Map.entry("snapshotHash",value.snapshotHash()),
			Map.entry("snapshot",value.snapshot()),Map.entry("errorCode",errorCode),Map.entry("now",value.createdAt().atOffset(ZoneOffset.UTC)))).update();
	}

	public Optional<LockedExecution> findForUpdate(UUID id, UUID teacherId, UUID requestId) {
		return jdbc.sql("""
			SELECT id,status,adapter_execution_id,ai_execution_id,ai_job_id,ai_set_id,target_index
			FROM problem_generation_executions
			WHERE id=:id AND teacher_id=:teacherId AND problem_request_id=:requestId FOR UPDATE
			""").param("id", id).param("teacherId", teacherId).param("requestId", requestId)
			.query((rs,row) -> new LockedExecution(rs.getObject("id",UUID.class),
				ProblemGenerationExecutionStatus.valueOf(rs.getString("status")),
				rs.getObject("adapter_execution_id",UUID.class), rs.getString("ai_execution_id"),
				rs.getString("ai_job_id"), rs.getString("ai_set_id"), rs.getInt("target_index"))).optional();
	}

	public void markDispatched(UUID id, UUID teacherId, Instant now) {
		jdbc.sql("""
			UPDATE problem_generation_executions SET status='DISPATCHED',
			dispatched_at=COALESCE(dispatched_at,:now),updated_at=:now
			WHERE id=:id AND teacher_id=:teacherId AND status='QUEUED'
			""").param("id",id).param("teacherId",teacherId).param("now",now.atOffset(ZoneOffset.UTC)).update();
	}

	public void markDeliveryFailed(UUID id, UUID teacherId, Instant now) {
		jdbc.sql("""
			UPDATE problem_generation_executions SET status='DELIVERY_FAILED',error_code='KAFKA_DELIVERY_FAILED',
			completed_at=:now,updated_at=:now WHERE id=:id AND teacher_id=:teacherId
			AND status IN ('QUEUED','DISPATCHED','DELIVERY_FAILED')
			""").param("id",id).param("teacherId",teacherId).param("now",now.atOffset(ZoneOffset.UTC)).update();
	}

	public void applyResult(ResultUpdate value) {
		jdbc.sql("""
			UPDATE problem_generation_executions SET status=:status,
			adapter_execution_id=COALESCE(:adapterId,adapter_execution_id),
			ai_execution_id=COALESCE(:aiExecutionId,ai_execution_id),ai_job_id=COALESCE(:jobId,ai_job_id),
			ai_set_id=COALESCE(:setId,ai_set_id),ai_result_status=COALESCE(:resultStatus,ai_result_status),
			error_code=:errorCode,result_payload=COALESCE(CAST(:result AS jsonb),result_payload),
			versions_payload=COALESCE(CAST(:versions AS jsonb),versions_payload),
			started_at=CASE WHEN :status='RUNNING' THEN COALESCE(started_at,:now) ELSE started_at END,
			completed_at=:completedAt,updated_at=:now
			WHERE id=:id AND teacher_id=:teacherId
			""").params(Map.ofEntries(Map.entry("id",value.id()),Map.entry("teacherId",value.teacherId()),
			Map.entry("status",value.status().name()),Map.entry("adapterId",nullable(value.adapterExecutionId())),
			Map.entry("aiExecutionId",nullable(value.aiExecutionId())),Map.entry("jobId",nullable(value.jobId())),
			Map.entry("setId",nullable(value.setId())),Map.entry("resultStatus",nullable(value.resultStatus())),
			Map.entry("errorCode",nullable(value.errorCode())),Map.entry("result",nullable(value.resultPayload())),
			Map.entry("versions",nullable(value.versionsPayload())),Map.entry("completedAt",nullableTime(value.completedAt())),
			Map.entry("now",value.updatedAt().atOffset(ZoneOffset.UTC)))).update();
	}

	public void applyWorkerReference(WorkerReference value) {
		boolean terminal = java.util.Set.of("succeeded", "failed", "cancelled").contains(value.workerPhase());
		ProblemGenerationExecutionStatus backendStatus;
		if ("failed".equals(value.workerPhase())) backendStatus = ProblemGenerationExecutionStatus.FAILED;
		else if ("cancelled".equals(value.workerPhase())) backendStatus = ProblemGenerationExecutionStatus.CANCELLED;
		else if ("succeeded".equals(value.workerPhase()) && "rejected_insufficient".equals(value.domainStatus()))
			backendStatus = ProblemGenerationExecutionStatus.REJECTED_INSUFFICIENT;
		else backendStatus = ProblemGenerationExecutionStatus.RUNNING;
		jdbc.sql("""
			UPDATE problem_generation_executions SET status=:status,
			worker_phase=:workerPhase,domain_status=COALESCE(:domainStatus,domain_status),
			adapter_execution_id=COALESCE(:adapterId,adapter_execution_id),
			ai_execution_id=COALESCE(:aiExecutionId,ai_execution_id),ai_job_id=COALESCE(:jobId,ai_job_id),
			ai_set_id=COALESCE(:setId,ai_set_id),ai_result_status=COALESCE(:resultStatus,ai_result_status),
			error_code=:errorCode,result_payload=COALESCE(CAST(:result AS jsonb),result_payload),
			versions_payload=COALESCE(CAST(:versions AS jsonb),versions_payload),
			requested_count=COALESCE(:requestedCount,requested_count),
			processed_count=COALESCE(:processedCount,processed_count),
			unstarted_count=COALESCE(:unstartedCount,unstarted_count),
			expected_slot_count=COALESCE(:requestedCount,expected_slot_count),
			status_counts=COALESCE(CAST(:statusCounts AS jsonb),status_counts),
			started_at=CASE WHEN :workerPhase IN ('leased','running','paused')
			    THEN COALESCE(started_at,:now) ELSE started_at END,
			terminal_received_at=CASE WHEN :terminal THEN COALESCE(terminal_received_at,:occurredAt)
			    ELSE terminal_received_at END,
			completed_at=CASE WHEN :status IN ('FAILED','CANCELLED','REJECTED_INSUFFICIENT')
			    THEN :occurredAt ELSE NULL END,
			updated_at=:now
			WHERE id=:id AND teacher_id=:teacherId
			""").params(Map.ofEntries(
			Map.entry("id",value.id()),Map.entry("teacherId",value.teacherId()),
			Map.entry("status",backendStatus.name()),Map.entry("workerPhase",value.workerPhase()),
			Map.entry("domainStatus",nullable(value.domainStatus())),Map.entry("adapterId",nullable(value.adapterExecutionId())),
			Map.entry("aiExecutionId",nullable(value.aiExecutionId())),Map.entry("jobId",nullable(value.jobId())),
			Map.entry("setId",nullable(value.setId())),Map.entry("resultStatus",nullable(value.resultStatus())),
			Map.entry("errorCode",nullable(value.errorCode())),Map.entry("result",nullable(value.resultPayload())),
			Map.entry("versions",nullable(value.versionsPayload())),Map.entry("requestedCount",nullable(value.requestedCount())),
			Map.entry("processedCount",nullable(value.processedCount())),Map.entry("unstartedCount",nullable(value.unstartedCount())),
			Map.entry("statusCounts",nullable(value.statusCountsPayload())),Map.entry("terminal",terminal),
			Map.entry("occurredAt",value.occurredAt().atOffset(ZoneOffset.UTC)),
			Map.entry("now",value.updatedAt().atOffset(ZoneOffset.UTC)))).update();
	}

	public void refreshSlotCompletion(UUID id, UUID teacherId, Instant now) {
		jdbc.sql("""
			UPDATE problem_generation_executions execution
			SET received_slot_count = slots.received,
			    status = CASE
			      WHEN execution.worker_phase = 'succeeded'
			       AND execution.expected_slot_count IS NOT NULL
			       AND slots.received = execution.expected_slot_count
			      THEN CASE execution.domain_status
			        WHEN 'rejected_insufficient' THEN 'REJECTED_INSUFFICIENT'
			        WHEN 'failed' THEN 'FAILED'
			        ELSE 'SUCCEEDED'
			      END
			      ELSE execution.status
			    END,
			    completed_at = CASE
			      WHEN execution.worker_phase = 'succeeded'
			       AND execution.expected_slot_count IS NOT NULL
			       AND slots.received = execution.expected_slot_count
			      THEN COALESCE(execution.terminal_received_at, :now)
			      ELSE execution.completed_at
			    END,
			    updated_at = :now
			FROM (
			  SELECT COUNT(*)::int AS received
			  FROM problem_generation_slots
			  WHERE problem_execution_id = :id AND teacher_id = :teacherId
			) slots
			WHERE execution.id = :id AND execution.teacher_id = :teacherId
			""").param("id",id).param("teacherId",teacherId)
			.param("now",now.atOffset(ZoneOffset.UTC)).update();
	}

	public List<ProblemGenerationExecutionStatus> statuses(UUID teacherId, UUID requestId) {
		return jdbc.sql("SELECT status FROM problem_generation_executions WHERE teacher_id=:teacherId AND problem_request_id=:requestId ORDER BY target_index")
			.param("teacherId",teacherId).param("requestId",requestId)
			.query((rs,row)->ProblemGenerationExecutionStatus.valueOf(rs.getString(1))).list();
	}

	private static Object nullable(Object value) { return value == null ? new SqlParameterValue(java.sql.Types.OTHER,null) : value; }
	private static Object nullableTime(Instant value) { return value == null ? nullable(null) : value.atOffset(ZoneOffset.UTC); }
	public record NewExecution(UUID id,UUID teacherId,UUID requestId,UUID targetId,int targetIndex,String aiIdempotencyKey,String snapshotHash,String snapshot,Instant createdAt) {}
	public record LockedExecution(UUID id,ProblemGenerationExecutionStatus status,UUID adapterExecutionId,String aiExecutionId,String jobId,String setId,int targetIndex) {}
	public record ResultUpdate(UUID id,UUID teacherId,ProblemGenerationExecutionStatus status,UUID adapterExecutionId,String aiExecutionId,String jobId,String setId,String resultStatus,String errorCode,String resultPayload,String versionsPayload,Instant completedAt,Instant updatedAt) {}
	public record WorkerReference(UUID id,UUID teacherId,String workerPhase,String domainStatus,
		UUID adapterExecutionId,String aiExecutionId,String jobId,String setId,String resultStatus,String errorCode,
		Integer requestedCount,Integer processedCount,Integer unstartedCount,String statusCountsPayload,
		String resultPayload,String versionsPayload,Instant occurredAt,Instant updatedAt) { }
}
