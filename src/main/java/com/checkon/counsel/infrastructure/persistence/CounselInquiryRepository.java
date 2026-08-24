package com.checkon.counsel.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.checkon.counsel.application.CreateCounselDraftCommand;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The original context behind a counsel draft request — student/class refs,
 * raw (unmasked) text, labels, facts. Kept so a topic correction can redraft
 * with the same inputs without the frontend resending everything; counsel
 * itself never echoes the request payload back, so this is the only copy.
 */
@Repository
public class CounselInquiryRepository {

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};
	private static final TypeReference<List<CreateCounselDraftCommand.DismissedSuggestion>> DISMISSED_SUGGESTION_LIST =
		new TypeReference<>() {
		};
	private static final TypeReference<List<CreateCounselDraftCommand.Fact>> FACT_LIST = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	public CounselInquiryRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	/** Inserts, or overwrites the stored context if the same inquiry_ref is drafted again. */
	public void upsert(NewInquiry value) {
		jdbc.sql("""
			INSERT INTO counsel_inquiries (
			 id, teacher_id, inquiry_ref, student_id, class_id, topic, urgency, received_at,
			 raw_text, labels, dismissed_suggestions, period_label, facts, created_at, updated_at
			) VALUES (:id, :teacherId, :inquiryRef, :studentId, :classId, :topic, :urgency, :receivedAt,
			 :rawText, CAST(:labels AS jsonb), CAST(:dismissedSuggestions AS jsonb), :periodLabel,
			 CAST(:facts AS jsonb), :createdAt, :updatedAt)
			ON CONFLICT (teacher_id, inquiry_ref) DO UPDATE SET
			 student_id = EXCLUDED.student_id,
			 class_id = EXCLUDED.class_id,
			 topic = EXCLUDED.topic,
			 urgency = EXCLUDED.urgency,
			 received_at = EXCLUDED.received_at,
			 raw_text = EXCLUDED.raw_text,
			 labels = EXCLUDED.labels,
			 dismissed_suggestions = EXCLUDED.dismissed_suggestions,
			 period_label = EXCLUDED.period_label,
			 facts = EXCLUDED.facts,
			 updated_at = EXCLUDED.updated_at
			""").params(Map.ofEntries(
				Map.entry("id", value.id()), Map.entry("teacherId", value.teacherId()),
				Map.entry("inquiryRef", value.inquiryRef()), Map.entry("studentId", value.studentId()),
				Map.entry("classId", value.classId()), Map.entry("topic", value.topic()),
				Map.entry("urgency", value.urgency()), Map.entry("receivedAt", time(value.receivedAt())),
				Map.entry("rawText", value.rawText()), Map.entry("labels", writeJson(value.labels())),
				Map.entry("dismissedSuggestions", writeJson(value.dismissedSuggestions())),
				Map.entry("periodLabel", value.periodLabel()), Map.entry("facts", writeJson(value.facts())),
				Map.entry("createdAt", time(value.createdAt())), Map.entry("updatedAt", time(value.updatedAt()))
			)).update();
	}

	public Optional<Inquiry> findByTeacherAndInquiryRef(UUID teacherId, String inquiryRef) {
		return jdbc.sql("""
			SELECT teacher_id, inquiry_ref, student_id, class_id, topic, urgency, received_at,
			 raw_text, labels::text AS labels, dismissed_suggestions::text AS dismissed_suggestions,
			 period_label, facts::text AS facts, created_at, updated_at
			FROM counsel_inquiries
			WHERE teacher_id = :teacherId AND inquiry_ref = :inquiryRef
			""").param("teacherId", teacherId).param("inquiryRef", inquiryRef)
			.query(this::map).optional();
	}

	private Inquiry map(ResultSet rs, int row) throws SQLException {
		return new Inquiry(
			rs.getObject("teacher_id", UUID.class), rs.getString("inquiry_ref"),
			rs.getObject("student_id", UUID.class), rs.getObject("class_id", UUID.class),
			rs.getString("topic"), rs.getString("urgency"), instant(rs, "received_at"),
			rs.getString("raw_text"), readJson(rs.getString("labels"), STRING_LIST),
			readJson(rs.getString("dismissed_suggestions"), DISMISSED_SUGGESTION_LIST),
			rs.getString("period_label"), readJson(rs.getString("facts"), FACT_LIST),
			instant(rs, "created_at"), instant(rs, "updated_at")
		);
	}

	private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
	private static Instant instant(ResultSet rs, String column) throws SQLException { return rs.getObject(column, OffsetDateTime.class).toInstant(); }

	private String writeJson(Object value) {
		try { return objectMapper.writeValueAsString(value); }
		catch (JacksonException exception) { throw new IllegalStateException("counsel inquiry context could not be serialized", exception); }
	}

	private <T> T readJson(String json, TypeReference<T> type) {
		try { return objectMapper.readValue(json, type); }
		catch (JacksonException exception) { throw new IllegalStateException("stored counsel inquiry context is invalid", exception); }
	}

	public record NewInquiry(
		UUID id, UUID teacherId, String inquiryRef, UUID studentId, UUID classId,
		String topic, String urgency, Instant receivedAt, String rawText,
		List<String> labels, List<CreateCounselDraftCommand.DismissedSuggestion> dismissedSuggestions,
		String periodLabel, List<CreateCounselDraftCommand.Fact> facts, Instant createdAt, Instant updatedAt
	) {
	}

	public record Inquiry(
		UUID teacherId, String inquiryRef, UUID studentId, UUID classId,
		String topic, String urgency, Instant receivedAt, String rawText,
		List<String> labels, List<CreateCounselDraftCommand.DismissedSuggestion> dismissedSuggestions,
		String periodLabel, List<CreateCounselDraftCommand.Fact> facts, Instant createdAt, Instant updatedAt
	) {
	}
}
