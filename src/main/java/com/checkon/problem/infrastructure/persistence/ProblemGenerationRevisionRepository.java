package com.checkon.problem.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProblemGenerationRevisionRepository {
	private final JdbcClient jdbc;

	public ProblemGenerationRevisionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public boolean insert(NewRevision value) {
		return jdbc.sql("""
			INSERT INTO problem_generation_revision_requests (
			 id,teacher_id,problem_request_id,problem_execution_id,slot_index,base_revision_no,
			 instruction,client_idempotency_key,request_hash,status,created_at,updated_at
			) VALUES (:id,:teacherId,:requestId,:executionId,:slotIndex,:baseRevision,
			 :instruction,:clientKey,:requestHash,'PENDING',:now,:now)
			ON CONFLICT (teacher_id,client_idempotency_key) DO NOTHING
			""").params(Map.ofEntries(
			Map.entry("id",value.id()),Map.entry("teacherId",value.teacherId()),
			Map.entry("requestId",value.requestId()),Map.entry("executionId",value.executionId()),
			Map.entry("slotIndex",value.slotIndex()),Map.entry("baseRevision",value.baseRevisionNo()),
			Map.entry("instruction",value.instruction()),Map.entry("clientKey",value.clientIdempotencyKey()),
			Map.entry("requestHash",value.requestHash()),Map.entry("now",time(value.createdAt())))).update() == 1;
	}

	public Optional<RevisionRow> findByClientKey(UUID teacherId, String clientKey) {
		return jdbc.sql("""
			SELECT id,problem_request_id,problem_execution_id,slot_index,base_revision_no,
			 request_hash,status,ai_execution_id,error_code
			FROM problem_generation_revision_requests
			WHERE teacher_id=:teacherId AND client_idempotency_key=:clientKey
			""").param("teacherId",teacherId).param("clientKey",clientKey)
			.query((rs,row)->new RevisionRow(rs.getObject("id",UUID.class),
				rs.getObject("problem_request_id",UUID.class),rs.getObject("problem_execution_id",UUID.class),
				rs.getInt("slot_index"),rs.getInt("base_revision_no"),rs.getString("request_hash"),
				rs.getString("status"),rs.getString("ai_execution_id"),rs.getString("error_code"))).optional();
	}

	public boolean hasActive(UUID teacherId, UUID executionId, int slotIndex) {
		return jdbc.sql("""
			SELECT EXISTS (
			 SELECT 1 FROM problem_generation_revision_requests
			 WHERE teacher_id=:teacherId AND problem_execution_id=:executionId AND slot_index=:slotIndex
			   AND status IN ('PENDING','DISPATCHED')
			)
			""").param("teacherId",teacherId).param("executionId",executionId)
			.param("slotIndex",slotIndex).query(Boolean.class).single();
	}

	public void markDispatched(UUID id, UUID teacherId, Instant now) {
		jdbc.sql("""
			UPDATE problem_generation_revision_requests SET status='DISPATCHED',updated_at=:now
			WHERE id=:id AND teacher_id=:teacherId AND status='PENDING'
			""").param("id",id).param("teacherId",teacherId).param("now",time(now)).update();
	}

	public void complete(UUID id, UUID teacherId, String status, String aiExecutionId,
		String errorCode, Instant occurredAt, Instant now) {
		jdbc.sql("""
			UPDATE problem_generation_revision_requests
			SET status=:status,ai_execution_id=COALESCE(:aiExecutionId,ai_execution_id),
			 error_code=:errorCode,completed_at=:occurredAt,updated_at=:now
			WHERE id=:id AND teacher_id=:teacherId AND status IN ('PENDING','DISPATCHED')
			""").param("id",id).param("teacherId",teacherId).param("status",status)
			.param("aiExecutionId",nullable(aiExecutionId)).param("errorCode",nullable(errorCode))
			.param("occurredAt",time(occurredAt)).param("now",time(now)).update();
	}

	private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
	private static Object nullable(Object value) {
		return value == null ? new SqlParameterValue(java.sql.Types.OTHER,null) : value;
	}

	public record NewRevision(UUID id,UUID teacherId,UUID requestId,UUID executionId,int slotIndex,
		int baseRevisionNo,String instruction,String clientIdempotencyKey,String requestHash,Instant createdAt) { }
	public record RevisionRow(UUID id,UUID requestId,UUID executionId,int slotIndex,int baseRevisionNo,
		String requestHash,String status,String aiExecutionId,String errorCode) { }
}
