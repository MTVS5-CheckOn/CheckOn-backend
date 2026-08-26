package com.checkon.problem.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.checkon.problem.domain.ProblemTypeTag;
import com.checkon.problem.domain.ProblemValidationStatus;

@Repository
public class ProblemStudioWorkflowRepository {
	private final JdbcClient jdbc;

	public ProblemStudioWorkflowRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<UUID> insertTargets(UUID teacherId, UUID requestId, List<NewTarget> targets) {
		java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
		for (int index = 0; index < targets.size(); index++) {
			NewTarget target = targets.get(index);
			ids.add(jdbc.sql("""
				INSERT INTO problem_generation_request_targets
				    (teacher_id, problem_request_id, ordinal, area_tag, type_tag, requested_count,
				     skill_node_id, source_payload)
				VALUES (:teacherId, :requestId, :ordinal, :areaTag, :typeTag, :count,
				    :skillNodeId, CAST(:sourcePayload AS jsonb))
				RETURNING id
				""").param("teacherId", teacherId).param("requestId", requestId)
				.param("ordinal", index + 1).param("areaTag", target.areaTag())
				.param("typeTag", target.typeTag().name()).param("count", target.count())
				.param("skillNodeId", target.skillNodeId()).param("sourcePayload", target.sourcePayload())
				.query(UUID.class).single());
		}
		return List.copyOf(ids);
	}

	public UUID insertItem(NewItem item) {
		return jdbc.sql("""
			INSERT INTO problem_generation_items (
			    teacher_id, problem_request_id, external_item_id, ordinal, stem, passage,
			    correct_answer_text, explanation, source_basis, validation_status,
			    validation_message, selected, raw_payload, skill_node_id, area_tag, type_tag,
			    correct_no, created_at, updated_at
			) VALUES (
			    :teacherId, :requestId, :externalId, :ordinal, :stem, :passage,
			    :correctAnswer, :explanation, :sourceBasis, :validationStatus,
			    :validationMessage, :selected, CAST(:rawPayload AS jsonb), :skillNodeId, :areaTag, :typeTag,
			    :correctNo, :now, :now
			) RETURNING id
			""").params(Map.ofEntries(
			Map.entry("teacherId", item.teacherId()), Map.entry("requestId", item.requestId()),
			Map.entry("externalId", nullable(item.externalId())), Map.entry("ordinal", item.ordinal()),
			Map.entry("stem", item.stem()), Map.entry("passage", nullable(item.passage())),
			Map.entry("correctAnswer", nullable(item.correctAnswer())),
			Map.entry("explanation", nullable(item.explanation())), Map.entry("sourceBasis", nullable(item.sourceBasis())),
			Map.entry("validationStatus", item.validationStatus().name()),
			Map.entry("validationMessage", nullable(item.validationMessage())),
			Map.entry("selected", item.validationStatus().publishable()), Map.entry("rawPayload", item.rawPayload()),
			Map.entry("skillNodeId", nullable(item.skillNodeId())),Map.entry("areaTag",nullable(item.areaTag())),
			Map.entry("typeTag",nullable(item.typeTag())),Map.entry("correctNo", nullable(item.correctNo())),
			Map.entry("now", item.now().atOffset(ZoneOffset.UTC))
		)).query(UUID.class).single();
	}

	public void insertOption(UUID teacherId, UUID requestId, UUID itemId, int position, String content,
		String whyWrong, String misconceptionTag) {
		jdbc.sql("""
			INSERT INTO problem_generation_item_options
			    (item_id, teacher_id, problem_request_id, position, content, why_wrong, misconception_tag)
			VALUES (:itemId, :teacherId, :requestId, :position, :content, :whyWrong, :misconceptionTag)
			""").param("itemId", itemId).param("teacherId", teacherId).param("requestId", requestId)
			.param("position", position).param("content", content)
			.param("whyWrong", nullable(whyWrong)).param("misconceptionTag", nullable(misconceptionTag)).update();
	}

	public void replaceItemForRevision(NewItem item, UUID itemId) {
		jdbc.sql("""
			UPDATE problem_generation_items SET stem=:stem,passage=:passage,
			 correct_answer_text=:correctAnswer,explanation=:explanation,source_basis=:sourceBasis,
			 validation_status=:validationStatus,validation_message=:validationMessage,
			 raw_payload=CAST(:rawPayload AS jsonb),skill_node_id=:skillNodeId,area_tag=:areaTag,
			 type_tag=:typeTag,correct_no=:correctNo,
			 selected=FALSE,updated_at=:now
			WHERE id=:itemId AND teacher_id=:teacherId AND problem_request_id=:requestId
			""").param("itemId",itemId).param("teacherId",item.teacherId()).param("requestId",item.requestId())
			.param("stem",item.stem()).param("passage",nullable(item.passage()))
			.param("correctAnswer",nullable(item.correctAnswer())).param("explanation",nullable(item.explanation()))
			.param("sourceBasis",nullable(item.sourceBasis())).param("validationStatus",item.validationStatus().name())
			.param("validationMessage",nullable(item.validationMessage())).param("rawPayload",item.rawPayload())
			.param("skillNodeId",nullable(item.skillNodeId())).param("correctNo",nullable(item.correctNo()))
			.param("areaTag",nullable(item.areaTag())).param("typeTag",nullable(item.typeTag()))
			.param("now",item.now().atOffset(ZoneOffset.UTC)).update();
		jdbc.sql("DELETE FROM problem_generation_item_options WHERE item_id=:itemId AND teacher_id=:teacherId")
			.param("itemId",itemId).param("teacherId",item.teacherId()).update();
	}

	public void updateSlotRevision(NewSlot slot) {
		jdbc.sql("""
			UPDATE problem_generation_slots SET status=:status,current_revision_no=:revision,
			 review_reason=:reviewReason,failure_reason=:failureReason,
			 failure_detail=CAST(:failureDetail AS jsonb),raw_payload=CAST(:raw AS jsonb),
			 ai_status=:aiStatus,available_actions=CAST(:availableActions AS jsonb),
			 revision_payload=CAST(:revisions AS jsonb),updated_at=:now
			WHERE teacher_id=:teacherId AND problem_request_id=:requestId
			  AND problem_execution_id=:executionId AND slot_index=:slotIndex
			""").params(Map.ofEntries(Map.entry("teacherId",slot.teacherId()),Map.entry("requestId",slot.requestId()),
			Map.entry("executionId",slot.executionId()),Map.entry("slotIndex",slot.slotIndex()),
			Map.entry("status",slot.status().name()),Map.entry("revision",slot.revision()),
			Map.entry("reviewReason",nullable(slot.reviewReason())),Map.entry("failureReason",nullable(slot.failureReason())),
			Map.entry("failureDetail",nullable(slot.failureDetail())),Map.entry("raw",slot.rawPayload()),
			Map.entry("aiStatus",nullable(slot.aiStatus())),Map.entry("availableActions",slot.availableActionsPayload()),
			Map.entry("revisions",slot.revisionsPayload()),Map.entry("now",slot.now().atOffset(ZoneOffset.UTC)))).update();
	}

	public int insertSlot(NewSlot slot) {
		return jdbc.sql("""
			INSERT INTO problem_generation_slots (
			 teacher_id,problem_request_id,problem_execution_id,slot_index,item_id,external_item_id,status,
			 current_revision_no,review_reason,failure_reason,failure_detail,raw_payload,ai_status,
			 available_actions,revision_payload,created_at,updated_at
			) VALUES (:teacherId,:requestId,:executionId,:slotIndex,:itemId,:externalId,:status,
			 :revision,:reviewReason,:failureReason,CAST(:failureDetail AS jsonb),CAST(:raw AS jsonb),:aiStatus,
			 CAST(:availableActions AS jsonb),CAST(:revisions AS jsonb),:now,:now)
			ON CONFLICT (problem_execution_id,slot_index) DO NOTHING
			""").params(Map.ofEntries(Map.entry("teacherId",slot.teacherId()),Map.entry("requestId",slot.requestId()),
			Map.entry("executionId",slot.executionId()),Map.entry("slotIndex",slot.slotIndex()),Map.entry("itemId",nullable(slot.itemId())),
			Map.entry("externalId",nullable(slot.externalItemId())),Map.entry("status",slot.status().name()),
			Map.entry("revision",slot.revision()),Map.entry("reviewReason",nullable(slot.reviewReason())),
			Map.entry("failureReason",nullable(slot.failureReason())),Map.entry("failureDetail",nullable(slot.failureDetail())),
			Map.entry("raw",slot.rawPayload()),Map.entry("aiStatus",nullable(slot.aiStatus())),
			Map.entry("availableActions",slot.availableActionsPayload()),Map.entry("revisions",slot.revisionsPayload()),
			Map.entry("now",slot.now().atOffset(ZoneOffset.UTC)))).update();
	}

	public void updateExecutionSummary(UUID teacherId,UUID executionId,int requested,int processed,String statusCounts) {
		jdbc.sql("""
			UPDATE problem_generation_executions SET requested_count=:requested,processed_count=:processed,
			 expected_slot_count=COALESCE(expected_slot_count,:requested),
			 status_counts=CAST(:counts AS jsonb) WHERE id=:executionId AND teacher_id=:teacherId
			""").param("requested",requested).param("processed",processed).param("counts",statusCounts)
			.param("executionId",executionId).param("teacherId",teacherId).update();
	}

	public void markProjection(UUID teacherId, UUID requestId, String status, String errorCode) {
		jdbc.sql("""
			UPDATE problem_generation_requests
			SET projection_status = :status, projection_error_code = :errorCode
			WHERE id = :requestId AND teacher_id = :teacherId
			""").param("status", status).param("errorCode", nullable(errorCode))
			.param("requestId", requestId).param("teacherId", teacherId).update();
	}

	public boolean hasSavedSet(UUID teacherId, UUID requestId) {
		return jdbc.sql("""
			SELECT EXISTS (SELECT 1 FROM saved_problem_sets
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId)
			""").param("teacherId", teacherId).param("requestId", requestId)
			.query(Boolean.class).single();
	}

	public Optional<RevisionTarget> findRevisionTarget(UUID teacherId, UUID requestId, UUID executionId,
		int slotIndex) {
		return jdbc.sql("""
			SELECT slot.item_id,slot.current_revision_no,slot.available_actions::text,
			       execution.ai_set_id
			FROM problem_generation_slots slot
			JOIN problem_generation_executions execution
			  ON execution.id=slot.problem_execution_id
			 AND execution.teacher_id=slot.teacher_id
			 AND execution.problem_request_id=slot.problem_request_id
			WHERE slot.teacher_id=:teacherId AND slot.problem_request_id=:requestId
			  AND slot.problem_execution_id=:executionId AND slot.slot_index=:slotIndex
			""").param("teacherId",teacherId).param("requestId",requestId)
			.param("executionId",executionId).param("slotIndex",slotIndex)
			.query((rs,row)->new RevisionTarget(rs.getObject("item_id",UUID.class),
				rs.getInt("current_revision_no"),rs.getString("available_actions"),rs.getString("ai_set_id")))
			.optional();
	}

	public int countItems(UUID teacherId, UUID requestId) {
		return jdbc.sql("""
			SELECT COUNT(*) FROM problem_generation_items
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId
			""").param("teacherId", teacherId).param("requestId", requestId).query(Integer.class).single();
	}

	public int maxItemOrdinal(UUID teacherId, UUID requestId) {
		return jdbc.sql("SELECT COALESCE(MAX(ordinal),0) FROM problem_generation_items WHERE teacher_id=:teacherId AND problem_request_id=:requestId")
			.param("teacherId",teacherId).param("requestId",requestId).query(Integer.class).single();
	}

	public int countPublishableSelection(UUID teacherId, UUID requestId) {
		return jdbc.sql("""
			SELECT COUNT(*) FROM problem_generation_items
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId
			  AND selected = TRUE AND validation_status IN ('PASSED', 'REVIEW_REQUIRED')
			""").param("teacherId", teacherId).param("requestId", requestId).query(Integer.class).single();
	}

	public int countRequestedItems(UUID teacherId, UUID requestId, List<UUID> itemIds) {
		if (itemIds.isEmpty()) return 0;
		return jdbc.sql("""
			SELECT COUNT(*) FROM problem_generation_items
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId
			  AND id IN (:itemIds) AND validation_status IN ('PASSED', 'REVIEW_REQUIRED')
			""").param("teacherId", teacherId).param("requestId", requestId)
			.param("itemIds", itemIds).query(Integer.class).single();
	}

	public void replaceSelection(UUID teacherId, UUID requestId, List<UUID> itemIds) {
		jdbc.sql("""
			UPDATE problem_generation_items SET selected = FALSE
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId AND selected = TRUE
			""").param("teacherId", teacherId).param("requestId", requestId).update();
		if (itemIds.isEmpty()) return;
		jdbc.sql("""
			UPDATE problem_generation_items SET selected = TRUE
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId
			  AND id IN (:itemIds) AND validation_status IN ('PASSED', 'REVIEW_REQUIRED')
			""").param("teacherId", teacherId).param("requestId", requestId)
			.param("itemIds", itemIds).update();
	}

	public SavedSetRow getOrCreateSavedSet(UUID teacherId, UUID requestId, Instant now) {
		jdbc.sql("""
			INSERT INTO saved_problem_sets
			    (teacher_id, problem_request_id, status, saved_at, updated_at)
			VALUES (:teacherId, :requestId, 'SAVED', :now, :now)
			ON CONFLICT (problem_request_id) DO NOTHING
			""").param("teacherId", teacherId).param("requestId", requestId)
			.param("now", now.atOffset(ZoneOffset.UTC))
			.update();
		return findSavedSet(teacherId, requestId)
			.orElseThrow(() -> new IllegalStateException("saved problem set was not visible after upsert"));
	}

	public Optional<SavedSetRow> findSavedSet(UUID teacherId, UUID requestId) {
		return jdbc.sql("""
			SELECT id, status, saved_at FROM saved_problem_sets
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId
			""").param("teacherId", teacherId).param("requestId", requestId)
			.query((rs, row) -> new SavedSetRow(
				rs.getObject("id", UUID.class), rs.getString("status"),
				rs.getObject("saved_at", java.time.OffsetDateTime.class).toInstant()
			)).optional();
	}

	public void snapshotSelectedItems(UUID teacherId, UUID requestId, UUID setId) {
		jdbc.sql("""
			INSERT INTO saved_problem_set_items
			    (problem_set_id, item_id, teacher_id, problem_request_id, ordinal, item_snapshot)
			SELECT :setId, item.id, item.teacher_id, item.problem_request_id,
			       ROW_NUMBER() OVER (ORDER BY item.ordinal)::int,
			       jsonb_build_object(
			           'itemId', item.id,
			           'ordinal', item.ordinal,
			           'skillNodeId', item.skill_node_id,
			           'areaTag', item.area_tag,
			           'typeTag', item.type_tag,
			           'stem', item.stem,
			           'passage', item.passage,
			           'correctNo', item.correct_no,
			           'correctAnswerText', item.correct_answer_text,
			           'explanation', item.explanation,
			           'sourceBasis', item.source_basis,
			           'validationStatus', item.validation_status,
			           'options', COALESCE((
			               SELECT jsonb_agg(jsonb_build_object(
			                   'position', option.position,
			                   'content', option.content,
			                   'whyWrong', option.why_wrong,
			                   'misconceptionTag', option.misconception_tag)
			                                ORDER BY option.position)
			               FROM problem_generation_item_options option WHERE option.item_id = item.id
			           ), '[]'::jsonb)
			       )
			FROM problem_generation_items item
			WHERE item.teacher_id = :teacherId AND item.problem_request_id = :requestId
			  AND item.selected = TRUE AND item.validation_status IN ('PASSED', 'REVIEW_REQUIRED')
			ON CONFLICT DO NOTHING
			""").param("setId", setId).param("teacherId", teacherId).param("requestId", requestId).update();
	}

	public AssignmentRow publish(UUID teacherId, UUID requestId, UUID setId, UUID studentId, Instant now) {
		jdbc.sql("""
			UPDATE saved_problem_sets
			SET status = 'PUBLISHED', published_at = COALESCE(published_at, :now), updated_at = :now
			WHERE id = :setId AND teacher_id = :teacherId AND problem_request_id = :requestId
			""").param("setId", setId).param("teacherId", teacherId).param("requestId", requestId)
			.param("now", now.atOffset(ZoneOffset.UTC)).update();
		jdbc.sql("""
			INSERT INTO problem_assignments
			    (teacher_id, problem_request_id, problem_set_id, student_id, status, published_at)
			VALUES (:teacherId, :requestId, :setId, :studentId, 'PUBLISHED', :now)
			ON CONFLICT (problem_request_id) DO NOTHING
			""").param("teacherId", teacherId).param("requestId", requestId).param("setId", setId)
			.param("studentId", studentId).param("now", now.atOffset(ZoneOffset.UTC)).update();
		return jdbc.sql("""
			SELECT id, problem_set_id, student_id, status, published_at
			FROM problem_assignments
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId
			""").param("teacherId", teacherId).param("requestId", requestId)
			.query((rs, row) -> new AssignmentRow(
				rs.getObject("id", UUID.class), rs.getObject("problem_set_id", UUID.class),
				rs.getObject("student_id", UUID.class), rs.getString("status"),
				rs.getObject("published_at", java.time.OffsetDateTime.class).toInstant()
			)).single();
	}

	public int savedItemCount(UUID teacherId, UUID setId) {
		return jdbc.sql("""
			SELECT COUNT(*) FROM saved_problem_set_items
			WHERE teacher_id = :teacherId AND problem_set_id = :setId
			""").param("teacherId", teacherId).param("setId", setId).query(Integer.class).single();
	}

	private static Object nullable(Object value) {
		return value == null ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.OTHER, null) : value;
	}

	public record NewItem(UUID teacherId, UUID requestId, String externalId, int ordinal,
		String stem, String passage, String correctAnswer, String explanation, String sourceBasis,
		ProblemValidationStatus validationStatus, String validationMessage, String skillNodeId,
		String areaTag,String typeTag,Integer correctNo,
		String rawPayload, Instant now) { }
	public record NewSlot(UUID teacherId,UUID requestId,UUID executionId,int slotIndex,UUID itemId,String externalItemId,
		ProblemValidationStatus status,int revision,String reviewReason,String failureReason,String failureDetail,
		String aiStatus,String availableActionsPayload,String revisionsPayload,String rawPayload,Instant now) { }
	public record SavedSetRow(UUID id, String status, Instant savedAt) { }
	public record AssignmentRow(UUID id, UUID setId, UUID studentId, String status, Instant publishedAt) { }
	public record NewTarget(String areaTag, ProblemTypeTag typeTag, int count, String skillNodeId,
		String sourcePayload) { }
	public record RevisionTarget(UUID itemId,int currentRevisionNo,String availableActionsPayload,
		String aiSetId) { }
}
