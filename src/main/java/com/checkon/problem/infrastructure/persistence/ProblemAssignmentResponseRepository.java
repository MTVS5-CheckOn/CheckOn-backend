package com.checkon.problem.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProblemAssignmentResponseRepository {
	private final JdbcClient jdbc;
	public ProblemAssignmentResponseRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

	public Optional<GradingSource> findGradingSource(UUID teacherId,UUID studentId,UUID assignmentId,UUID itemId) {
		return jdbc.sql("""
			SELECT assignment.problem_set_id,saved.item_snapshot::text
			FROM problem_assignments assignment
			JOIN saved_problem_set_items saved
			  ON saved.problem_set_id=assignment.problem_set_id
			 AND saved.teacher_id=assignment.teacher_id
			WHERE assignment.id=:assignmentId AND assignment.teacher_id=:teacherId
			  AND assignment.student_id=:studentId AND saved.item_id=:itemId
			""").param("assignmentId",assignmentId).param("teacherId",teacherId)
			.param("studentId",studentId).param("itemId",itemId)
			.query((rs,row)->new GradingSource(rs.getObject("problem_set_id",UUID.class),
				rs.getString("item_snapshot"))).optional();
	}

	public boolean insert(NewResponse value) {
		return jdbc.sql("""
			INSERT INTO problem_assignment_responses (
			 teacher_id,assignment_id,student_id,problem_set_id,item_id,chosen_no,correct_no,
			 correct,area_tag,type_tag,skill_node_id,misconception_tag,responded_at,created_at
			) VALUES (:teacherId,:assignmentId,:studentId,:setId,:itemId,:chosenNo,:correctNo,
			 :correct,:areaTag,:typeTag,:skillNodeId,:misconceptionTag,:respondedAt,:createdAt)
			ON CONFLICT (assignment_id,item_id) DO NOTHING
			""").params(Map.ofEntries(Map.entry("teacherId",value.teacherId()),
			Map.entry("assignmentId",value.assignmentId()),Map.entry("studentId",value.studentId()),
			Map.entry("setId",value.setId()),Map.entry("itemId",value.itemId()),
			Map.entry("chosenNo",value.chosenNo()),Map.entry("correctNo",value.correctNo()),
			Map.entry("correct",value.correct()),Map.entry("skillNodeId",value.skillNodeId()),
			Map.entry("areaTag",value.areaTag()),Map.entry("typeTag",value.typeTag()),
			Map.entry("misconceptionTag",nullable(value.misconceptionTag())),
			Map.entry("respondedAt",time(value.respondedAt())),Map.entry("createdAt",time(value.createdAt())))).update()==1;
	}

	public Optional<StoredResponse> find(UUID teacherId,UUID assignmentId,UUID itemId) {
		return jdbc.sql("""
			SELECT chosen_no,correct_no,correct,skill_node_id,misconception_tag,responded_at
			FROM problem_assignment_responses
			WHERE teacher_id=:teacherId AND assignment_id=:assignmentId AND item_id=:itemId
			""").param("teacherId",teacherId).param("assignmentId",assignmentId).param("itemId",itemId)
			.query((rs,row)->new StoredResponse(rs.getInt("chosen_no"),rs.getInt("correct_no"),
				rs.getBoolean("correct"),rs.getString("skill_node_id"),rs.getString("misconception_tag"),
				rs.getObject("responded_at",OffsetDateTime.class).toInstant())).optional();
	}

	private static OffsetDateTime time(Instant value){return value.atOffset(ZoneOffset.UTC);}
	private static Object nullable(Object value){return value==null
		?new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.OTHER,null):value;}
	public record GradingSource(UUID setId,String itemSnapshot) { }
	public record NewResponse(UUID teacherId,UUID assignmentId,UUID studentId,UUID setId,UUID itemId,
		int chosenNo,int correctNo,boolean correct,String areaTag,String typeTag,String skillNodeId,String misconceptionTag,
		Instant respondedAt,Instant createdAt) { }
	public record StoredResponse(int chosenNo,int correctNo,boolean correct,String skillNodeId,
		String misconceptionTag,Instant respondedAt) { }
}
