package com.checkon.problem.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.checkon.problem.application.ProblemStudioViews.Option;
import com.checkon.problem.application.ProblemStudioViews.ReviewItem;
import com.checkon.problem.application.ProblemStudioViews.ReviewSlot;
import com.checkon.problem.domain.ProblemValidationStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ProblemStudioQueryRepository {
	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	public ProblemStudioQueryRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public List<StudentRow> findStudents(UUID teacherId, Instant signalSince, int page, int size) {
		return jdbc.sql("""
			SELECT student.id AS student_id,
			       COALESCE(personal.real_name, student.alias) AS student_name,
			       class_group.name AS class_name,
			       class_group.subject,
			       relationship.started_at,
			       COUNT(alert.id)::int AS recent_signal_count
			FROM teacher_student_relationships relationship
			JOIN student_profiles student ON student.id = relationship.student_id
			LEFT JOIN student_personal_information personal ON personal.student_id = student.id
			LEFT JOIN class_enrollments enrollment
			  ON enrollment.student_id = student.id
			 AND enrollment.teacher_id = relationship.teacher_id
			 AND enrollment.status = 'ACTIVE'
			LEFT JOIN class_groups class_group
			  ON class_group.id = enrollment.class_group_id
			 AND class_group.teacher_id = relationship.teacher_id
			LEFT JOIN engagement_alerts alert
			  ON alert.teacher_id = relationship.teacher_id
			 AND alert.student_id = student.id
			 AND alert.created_at >= :signalSince
			WHERE relationship.teacher_id = :teacherId
			  AND relationship.status = 'ACTIVE'
			GROUP BY student.id, personal.real_name, student.alias, class_group.name,
			         class_group.subject, relationship.started_at
			ORDER BY COALESCE(personal.real_name, student.alias) ASC, student.id ASC
			LIMIT :size OFFSET :offset
			""")
			.param("teacherId", teacherId)
			.param("signalSince", signalSince.atOffset(ZoneOffset.UTC))
			.param("size", size)
			.param("offset", page * size)
			.query((rs, row) -> new StudentRow(
				rs.getObject("student_id", UUID.class),
				rs.getString("student_name"),
				rs.getString("class_name"),
				rs.getString("subject"),
				instant(rs, "started_at"),
				rs.getInt("recent_signal_count")
			)).list();
	}

	public long countStudents(UUID teacherId) {
		return jdbc.sql("""
			SELECT COUNT(*) FROM teacher_student_relationships
			WHERE teacher_id = :teacherId AND status = 'ACTIVE'
			""").param("teacherId", teacherId).query(Long.class).single();
	}

	public boolean hasActiveStudent(UUID teacherId, UUID studentId) {
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1 FROM teacher_student_relationships
			    WHERE teacher_id = :teacherId AND student_id = :studentId AND status = 'ACTIVE'
			)
			""").param("teacherId", teacherId).param("studentId", studentId)
			.query(Boolean.class).single();
	}

	public OverallAccuracy findOverallAccuracy(UUID teacherId, UUID studentId, Instant from, Instant to) {
		return jdbc.sql("""
			SELECT COUNT(*)::int AS solved_count,
			       COUNT(*) FILTER (WHERE correct)::int AS correct_count
			FROM learning_records
			WHERE teacher_id = :teacherId AND student_id = :studentId
			  AND record_type = 'SOLVE' AND correct IS NOT NULL
			  AND area_tag IS NOT NULL AND type_tag IS NOT NULL
			  AND occurred_at >= :from AND occurred_at < :to
			""").param("teacherId", teacherId).param("studentId", studentId)
			.param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC))
			.query((rs, row) -> new OverallAccuracy(rs.getInt("solved_count"), rs.getInt("correct_count")))
			.single();
	}

	public List<WeaknessRow> findWeaknessRows(UUID teacherId, UUID studentId, Instant from, Instant to) {
		return jdbc.sql("""
			SELECT area_tag, UPPER(type_tag) AS type_tag,
			       COUNT(*)::int AS solved_count,
			       COUNT(*) FILTER (WHERE correct)::int AS correct_count
			FROM learning_records
			WHERE teacher_id = :teacherId AND student_id = :studentId
			  AND record_type = 'SOLVE' AND correct IS NOT NULL
			  AND area_tag IS NOT NULL AND btrim(area_tag) <> ''
			  AND UPPER(type_tag) IN ('FACT', 'INFER', 'CRITIC', 'CONCEPT')
			  AND occurred_at >= :from AND occurred_at < :to
			GROUP BY area_tag, UPPER(type_tag)
			ORDER BY area_tag ASC, UPPER(type_tag) ASC
			""").param("teacherId", teacherId).param("studentId", studentId)
			.param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC))
			.query((rs, row) -> new WeaknessRow(
				rs.getString("area_tag"), rs.getString("type_tag"),
				rs.getInt("solved_count"), rs.getInt("correct_count")
			)).list();
	}

	public Optional<RequestRow> findRequest(UUID teacherId, UUID requestId) {
		return jdbc.sql("""
			SELECT id, teacher_id, target_kind, student_id, status,
			       projection_status, projection_error_code
			FROM problem_generation_requests
			WHERE id = :requestId AND teacher_id = :teacherId
			""").param("requestId", requestId).param("teacherId", teacherId)
			.query((rs, row) -> new RequestRow(
				rs.getObject("id", UUID.class), rs.getString("target_kind"),
				rs.getObject("student_id", UUID.class), rs.getString("status"),
				rs.getString("projection_status"), rs.getString("projection_error_code")
			)).optional();
	}

	public List<ReviewItem> findReviewItems(UUID teacherId, UUID requestId, boolean selectedOnly) {
		List<ItemRow> items = jdbc.sql("""
			SELECT id, external_item_id, ordinal, area_tag, type_tag, skill_node_id, correct_no,
			       stem, passage, correct_answer_text,
			       explanation, source_basis, validation_status, validation_message, selected
			FROM problem_generation_items
			WHERE teacher_id = :teacherId AND problem_request_id = :requestId
			  AND (:selectedOnly = FALSE OR selected = TRUE)
			ORDER BY ordinal ASC
			""").param("teacherId", teacherId).param("requestId", requestId)
			.param("selectedOnly", selectedOnly)
			.query((rs, row) -> new ItemRow(
				rs.getObject("id", UUID.class), rs.getString("external_item_id"), rs.getInt("ordinal"),
				rs.getString("area_tag"), rs.getString("type_tag"), rs.getString("skill_node_id"),
				rs.getInt("correct_no"),
				rs.getString("stem"), rs.getString("passage"), rs.getString("correct_answer_text"),
				rs.getString("explanation"), rs.getString("source_basis"),
				ProblemValidationStatus.valueOf(rs.getString("validation_status")),
				rs.getString("validation_message"), rs.getBoolean("selected")
			)).list();
		if (items.isEmpty()) return List.of();

		Map<UUID, List<Option>> options = new LinkedHashMap<>();
		jdbc.sql("""
			SELECT option.item_id, option.position, option.content, option.why_wrong,
			       option.misconception_tag, item.correct_answer_text
			FROM problem_generation_item_options option
			JOIN problem_generation_items item
			  ON item.id = option.item_id
			 AND item.teacher_id = option.teacher_id
			 AND item.problem_request_id = option.problem_request_id
			WHERE option.teacher_id = :teacherId AND option.problem_request_id = :requestId
			ORDER BY option.item_id, option.position
			""").param("teacherId", teacherId).param("requestId", requestId)
			.query((rs, row) -> new OptionRow(
				rs.getObject("item_id", UUID.class), rs.getInt("position"),
				rs.getString("content"), rs.getString("correct_answer_text"),
				rs.getString("why_wrong"), rs.getString("misconception_tag")
			)).list().forEach(option -> options.computeIfAbsent(option.itemId(), ignored -> new ArrayList<>())
				.add(new Option(option.position(), option.content(), option.content().equals(option.correctAnswer()),
					option.whyWrong(), option.misconceptionTag())));

		return items.stream().map(item -> new ReviewItem(
			item.id(), item.ordinal(), item.externalItemId(), item.areaTag(), item.typeTag(),
			item.skillNodeId(), item.correctNo(), item.stem(), item.passage(),
			List.copyOf(options.getOrDefault(item.id(), List.of())), item.correctAnswerText(),
			item.explanation(), item.sourceBasis(), item.validationStatus(),
			item.validationMessage(), item.selected()
		)).toList();
	}

	public List<ReviewSlot> findReviewSlots(UUID teacherId,UUID requestId) {
		return jdbc.sql("""
			SELECT problem_execution_id,slot_index,item_id,external_item_id,status,current_revision_no,
			       available_actions::text,review_reason,failure_reason
			FROM problem_generation_slots WHERE teacher_id=:teacherId AND problem_request_id=:requestId
			ORDER BY problem_execution_id,slot_index
			""").param("teacherId",teacherId).param("requestId",requestId).query((rs,row)->new ReviewSlot(
			rs.getObject("problem_execution_id",UUID.class),rs.getInt("slot_index"),
			rs.getObject("item_id",UUID.class),rs.getString("external_item_id"),
			ProblemValidationStatus.valueOf(rs.getString("status")),rs.getInt("current_revision_no"),
			actions(rs.getString("available_actions")),rs.getString("review_reason"),rs.getString("failure_reason"))).list();
	}

	private List<String> actions(String json) {
		try { var root=objectMapper.readTree(json); List<String> result=new ArrayList<>();
			if(root!=null&&root.isArray()) for(var value:root) if(value.isTextual()) result.add(value.asText());
			return List.copyOf(result); }
		catch(JacksonException exception) { throw new IllegalStateException("stored available_actions is invalid",exception); }
	}

	public Optional<PrintableStudent> findPrintableStudent(UUID teacherId, UUID requestId) {
		return jdbc.sql("""
			SELECT request.student_id,
			       COALESCE(personal.real_name, student.alias) AS student_name,
			       class_group.name AS class_name, class_group.subject
			FROM problem_generation_requests request
			JOIN student_profiles student ON student.id = request.student_id
			LEFT JOIN student_personal_information personal ON personal.student_id = student.id
			LEFT JOIN class_enrollments enrollment
			  ON enrollment.student_id = student.id AND enrollment.teacher_id = request.teacher_id
			 AND enrollment.status = 'ACTIVE'
			LEFT JOIN class_groups class_group
			  ON class_group.id = enrollment.class_group_id AND class_group.teacher_id = request.teacher_id
			WHERE request.id = :requestId AND request.teacher_id = :teacherId
			  AND request.target_kind = 'STUDENT'
			""").param("requestId", requestId).param("teacherId", teacherId)
			.query((rs, row) -> new PrintableStudent(
				rs.getObject("student_id", UUID.class), rs.getString("student_name"),
				rs.getString("class_name"), rs.getString("subject")
			)).optional();
	}

	public record StudentRow(UUID studentId, String studentName, String className, String subject,
		Instant startedAt, int recentSignalCount) { }
	public record OverallAccuracy(int solvedCount, int correctCount) { }
	public record WeaknessRow(String areaTag, String typeTag, int solvedCount, int correctCount) { }
	public record RequestRow(UUID id, String targetKind, UUID studentId, String status,
		String projectionStatus, String projectionErrorCode) { }
	public record PrintableStudent(UUID studentId, String studentName, String className, String subject) { }
	private record ItemRow(UUID id, String externalItemId, int ordinal, String areaTag, String typeTag,
		String skillNodeId, int correctNo, String stem, String passage,
		String correctAnswerText, String explanation, String sourceBasis,
		ProblemValidationStatus validationStatus, String validationMessage, boolean selected) { }
	private record OptionRow(UUID itemId, int position, String content, String correctAnswer,
		String whyWrong, String misconceptionTag) { }

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
