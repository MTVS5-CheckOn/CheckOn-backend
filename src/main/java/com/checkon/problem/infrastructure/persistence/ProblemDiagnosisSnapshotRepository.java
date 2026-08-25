package com.checkon.problem.infrastructure.persistence;

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

@Repository
public class ProblemDiagnosisSnapshotRepository {
	private final JdbcClient jdbc;
	public ProblemDiagnosisSnapshotRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

	public List<LearningEvent> findEvents(UUID teacherId, UUID studentId, Instant from, Instant to) {
		return jdbc.sql("""
			SELECT id,area_tag,LOWER(type_tag) AS type_tag,correct,occurred_at
			FROM learning_records
			WHERE teacher_id=:teacherId AND student_id=:studentId
			  AND record_type='SOLVE' AND correct IS NOT NULL
			  AND area_tag IS NOT NULL AND btrim(area_tag)<>''
			  AND LOWER(type_tag) IN ('fact','infer','critic','concept')
			  AND occurred_at>=:from AND occurred_at<:to
			ORDER BY occurred_at,id
			""").param("teacherId",teacherId).param("studentId",studentId)
			.param("from",time(from)).param("to",time(to))
			.query((rs,row)->new LearningEvent(rs.getObject("id",UUID.class),rs.getString("area_tag"),
				rs.getString("type_tag"),rs.getBoolean("correct"),instant(rs,"occurred_at"))).list();
	}

	public List<ProblemResponseEvent> findProblemResponses(UUID teacherId,UUID studentId,Instant from,Instant to) {
		return jdbc.sql("""
			SELECT id,area_tag,type_tag,chosen_no,correct_no,correct,misconception_tag,
			       skill_node_id,responded_at
			FROM problem_assignment_responses
			WHERE teacher_id=:teacherId AND student_id=:studentId
			  AND responded_at>=:from AND responded_at<:to
			ORDER BY responded_at,id
			""").param("teacherId",teacherId).param("studentId",studentId)
			.param("from",time(from)).param("to",time(to))
			.query((rs,row)->new ProblemResponseEvent(rs.getObject("id",UUID.class),rs.getString("area_tag"),
				rs.getString("type_tag"),rs.getInt("chosen_no"),rs.getInt("correct_no"),
				rs.getBoolean("correct"),rs.getString("misconception_tag"),rs.getString("skill_node_id"),
				instant(rs,"responded_at"))).list();
	}

	public void insert(Snapshot value) {
		jdbc.sql("""
			INSERT INTO problem_diagnosis_snapshots (
			 id,teacher_id,student_id,student_ref,status,status_reason,snapshot_hash,
			 taxonomy_version,graph_version,config_version,request_payload,response_payload,
			 diagnosed_at,created_at
			) VALUES (:id,:teacherId,:studentId,:studentRef,:status,:reason,:hash,
			 :taxonomy,:graph,:config,CAST(:request AS jsonb),CAST(:response AS jsonb),:diagnosedAt,:createdAt)
			""").params(Map.ofEntries(
			Map.entry("id",value.id()),Map.entry("teacherId",value.teacherId()),Map.entry("studentId",value.studentId()),
			Map.entry("studentRef",value.studentRef()),Map.entry("status",value.status()),Map.entry("reason",nullable(value.statusReason())),
			Map.entry("hash",nullable(value.snapshotHash())),Map.entry("taxonomy",nullable(value.taxonomyVersion())),
			Map.entry("graph",nullable(value.graphVersion())),Map.entry("config",nullable(value.configVersion())),
			Map.entry("request",value.requestPayload()),Map.entry("response",nullable(value.responsePayload())),
			Map.entry("diagnosedAt",time(value.diagnosedAt())),Map.entry("createdAt",time(value.createdAt())))).update();
	}

	public Optional<Snapshot> find(UUID id, UUID teacherId, UUID studentId) {
		return jdbc.sql("""
			SELECT id,teacher_id,student_id,student_ref,status,status_reason,snapshot_hash,
			 taxonomy_version,graph_version,config_version,request_payload::text,response_payload::text,
			 diagnosed_at,created_at
			FROM problem_diagnosis_snapshots
			WHERE id=:id AND teacher_id=:teacherId AND student_id=:studentId
			""").param("id",id).param("teacherId",teacherId).param("studentId",studentId)
			.query(ProblemDiagnosisSnapshotRepository::map).optional();
	}

	private static Snapshot map(ResultSet rs,int row) throws SQLException {
		return new Snapshot(rs.getObject("id",UUID.class),rs.getObject("teacher_id",UUID.class),rs.getObject("student_id",UUID.class),
			rs.getString("student_ref"),rs.getString("status"),rs.getString("status_reason"),rs.getString("snapshot_hash"),
			rs.getString("taxonomy_version"),rs.getString("graph_version"),rs.getString("config_version"),
			rs.getString("request_payload"),rs.getString("response_payload"),instant(rs,"diagnosed_at"),instant(rs,"created_at"));
	}
	private static Object nullable(Object value) { return value == null ? new SqlParameterValue(Types.OTHER,null) : value; }
	private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
	private static Instant instant(ResultSet rs,String column) throws SQLException { return rs.getObject(column,OffsetDateTime.class).toInstant(); }

	public record LearningEvent(UUID id,String areaTag,String typeTag,boolean correct,Instant occurredAt) { }
	public record ProblemResponseEvent(UUID id,String areaTag,String typeTag,int chosenNo,int correctNo,
		boolean correct,String misconceptionTag,String skillNodeId,Instant occurredAt) { }
	public record Snapshot(UUID id,UUID teacherId,UUID studentId,String studentRef,String status,String statusReason,
		String snapshotHash,String taxonomyVersion,String graphVersion,String configVersion,String requestPayload,
		String responsePayload,Instant diagnosedAt,Instant createdAt) { }
}
