package com.checkon.counsel.infrastructure.persistence;

import java.math.BigDecimal;
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
 * Local mirror of classify predictions and teacher corrections, keyed by
 * (teacher, inquiry_ref). Necessary, not just convenient: classify is cached
 * upstream by {@code (tenant_id, inquiry_ref)} and will keep returning the
 * pre-correction prediction, so the corrected columns here are the only
 * durable record of "what the teacher actually said this inquiry is".
 */
@Repository
public class InquiryClassificationRepository {

	private final JdbcClient jdbc;

	public InquiryClassificationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Inserts a fresh prediction, or overwrites the prior one if classify was called again for the same inquiry_ref. */
	public void upsertPredicted(NewClassification value) {
		jdbc.sql("""
			INSERT INTO counsel_inquiry_classifications (
			 id, teacher_id, tenant_alias, inquiry_ref, predicted_topic, predicted_sentiment, predicted_urgency,
			 confidence_topic, confidence_sentiment, confidence_urgency, classified, fallback_reason,
			 ai_execution_id, classified_at, updated_at
			) VALUES (:id, :teacherId, :tenantAlias, :inquiryRef, :predictedTopic, :predictedSentiment, :predictedUrgency,
			 :confidenceTopic, :confidenceSentiment, :confidenceUrgency, :classified, :fallbackReason,
			 :aiExecutionId, :classifiedAt, :updatedAt)
			ON CONFLICT (teacher_id, inquiry_ref) DO UPDATE SET
			 predicted_topic = EXCLUDED.predicted_topic,
			 predicted_sentiment = EXCLUDED.predicted_sentiment,
			 predicted_urgency = EXCLUDED.predicted_urgency,
			 confidence_topic = EXCLUDED.confidence_topic,
			 confidence_sentiment = EXCLUDED.confidence_sentiment,
			 confidence_urgency = EXCLUDED.confidence_urgency,
			 classified = EXCLUDED.classified,
			 fallback_reason = EXCLUDED.fallback_reason,
			 ai_execution_id = EXCLUDED.ai_execution_id,
			 classified_at = EXCLUDED.classified_at,
			 updated_at = EXCLUDED.updated_at
			""").params(Map.ofEntries(
				Map.entry("id", value.id()), Map.entry("teacherId", value.teacherId()),
				Map.entry("tenantAlias", value.tenantAlias()), Map.entry("inquiryRef", value.inquiryRef()),
				Map.entry("predictedTopic", value.predictedTopic()), Map.entry("predictedSentiment", value.predictedSentiment()),
				Map.entry("predictedUrgency", value.predictedUrgency()), Map.entry("confidenceTopic", value.confidenceTopic()),
				Map.entry("confidenceSentiment", value.confidenceSentiment()), Map.entry("confidenceUrgency", value.confidenceUrgency()),
				Map.entry("classified", value.classified()), Map.entry("fallbackReason", nullable(value.fallbackReason())),
				Map.entry("aiExecutionId", nullable(value.aiExecutionId())), Map.entry("classifiedAt", time(value.classifiedAt())),
				Map.entry("updatedAt", time(value.updatedAt()))
			)).update();
	}

	/**
	 * Records a confirm/correct action. A supplied axis overwrites the stored
	 * correction for that axis; an axis left {@code null} keeps whatever was
	 * corrected before (partial correction, not a full replacement).
	 * Returns {@code false} if no local row exists for this (teacher, inquiry_ref).
	 */
	public boolean recordConfirmation(
		UUID teacherId,
		String inquiryRef,
		String confirmationAction,
		String correctedTopic,
		String correctedSentiment,
		String correctedUrgency,
		Instant confirmedAt
	) {
		int updated = jdbc.sql("""
			UPDATE counsel_inquiry_classifications SET
			 confirmation_action = :confirmationAction,
			 corrected_topic = COALESCE(:correctedTopic, corrected_topic),
			 corrected_sentiment = COALESCE(:correctedSentiment, corrected_sentiment),
			 corrected_urgency = COALESCE(:correctedUrgency, corrected_urgency),
			 confirmed_at = :confirmedAt,
			 updated_at = :confirmedAt
			WHERE teacher_id = :teacherId AND inquiry_ref = :inquiryRef
			""").param("confirmationAction", confirmationAction)
			.param("correctedTopic", nullable(correctedTopic))
			.param("correctedSentiment", nullable(correctedSentiment))
			.param("correctedUrgency", nullable(correctedUrgency))
			.param("confirmedAt", time(confirmedAt))
			.param("teacherId", teacherId).param("inquiryRef", inquiryRef)
			.update();
		return updated > 0;
	}

	public Optional<Classification> findByTeacherAndInquiryRef(UUID teacherId, String inquiryRef) {
		return jdbc.sql("""
			SELECT teacher_id, tenant_alias, inquiry_ref, predicted_topic, predicted_sentiment, predicted_urgency,
			 confidence_topic, confidence_sentiment, confidence_urgency, classified, fallback_reason,
			 ai_execution_id, classified_at, corrected_topic, corrected_sentiment, corrected_urgency,
			 confirmation_action, confirmed_at, updated_at
			FROM counsel_inquiry_classifications
			WHERE teacher_id = :teacherId AND inquiry_ref = :inquiryRef
			""").param("teacherId", teacherId).param("inquiryRef", inquiryRef)
			.query(InquiryClassificationRepository::map).optional();
	}

	private static Classification map(ResultSet rs, int row) throws SQLException {
		return new Classification(
			rs.getObject("teacher_id", UUID.class), rs.getString("tenant_alias"), rs.getString("inquiry_ref"),
			rs.getString("predicted_topic"), rs.getString("predicted_sentiment"), rs.getString("predicted_urgency"),
			rs.getBigDecimal("confidence_topic"), rs.getBigDecimal("confidence_sentiment"), rs.getBigDecimal("confidence_urgency"),
			rs.getBoolean("classified"), rs.getString("fallback_reason"), rs.getString("ai_execution_id"),
			instant(rs, "classified_at"), rs.getString("corrected_topic"), rs.getString("corrected_sentiment"),
			rs.getString("corrected_urgency"), rs.getString("confirmation_action"), instantOrNull(rs, "confirmed_at"),
			instant(rs, "updated_at")
		);
	}

	private static Object nullable(Object value) { return value == null ? new SqlParameterValue(Types.OTHER, null) : value; }
	private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
	private static Instant instant(ResultSet rs, String column) throws SQLException { return rs.getObject(column, OffsetDateTime.class).toInstant(); }
	private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	public record NewClassification(
		UUID id, UUID teacherId, String tenantAlias, String inquiryRef,
		String predictedTopic, String predictedSentiment, String predictedUrgency,
		BigDecimal confidenceTopic, BigDecimal confidenceSentiment, BigDecimal confidenceUrgency,
		boolean classified, String fallbackReason, String aiExecutionId, Instant classifiedAt, Instant updatedAt
	) {
	}

	public record Classification(
		UUID teacherId, String tenantAlias, String inquiryRef,
		String predictedTopic, String predictedSentiment, String predictedUrgency,
		BigDecimal confidenceTopic, BigDecimal confidenceSentiment, BigDecimal confidenceUrgency,
		boolean classified, String fallbackReason, String aiExecutionId, Instant classifiedAt,
		String correctedTopic, String correctedSentiment, String correctedUrgency,
		String confirmationAction, Instant confirmedAt, Instant updatedAt
	) {
		/** The topic BE should actually use — the teacher's correction if there is one, else the AI's prediction. */
		public String effectiveTopic() { return correctedTopic != null ? correctedTopic : predictedTopic; }
		public String effectiveSentiment() { return correctedSentiment != null ? correctedSentiment : predictedSentiment; }
		public String effectiveUrgency() { return correctedUrgency != null ? correctedUrgency : predictedUrgency; }
	}
}
