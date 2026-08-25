package com.checkon.counsel.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;

@Repository
public class GuardianLabelSuggestionRepository {

	private final JdbcTemplate jdbcTemplate;

	public GuardianLabelSuggestionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<CachedSuggestions> findCached(UUID teacherId, UUID parentId, int count, String latestRecordId) {
		var requests = jdbcTemplate.query("""
			SELECT id, guardian_ref, ai_execution_id FROM guardian_label_suggestion_requests
			WHERE teacher_id = ? AND parent_id = ? AND history_count = ? AND latest_record_id = ?
			""", (rs, row) -> new RequestRow(
			(UUID) rs.getObject("id"), rs.getString("guardian_ref"), rs.getString("ai_execution_id")
		), teacherId, parentId, count, latestRecordId);
		if (requests.isEmpty()) return Optional.empty();
		var request = requests.getFirst();
		return Optional.of(new CachedSuggestions(
			request.id(), request.guardianRef(), request.executionId(), findSuggestions(request.id())
		));
	}

	public UUID insertRequestIfAbsent(NewRequest request) {
		var inserted = jdbcTemplate.query("""
			INSERT INTO guardian_label_suggestion_requests (
			 id, teacher_id, parent_id, guardian_ref, history_count, latest_record_id,
			 history, ai_execution_id, ai_versions, created_at
			) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)
			ON CONFLICT (teacher_id, parent_id, history_count, latest_record_id) DO NOTHING
			RETURNING id
			""", (rs, row) -> (UUID) rs.getObject(1), request.id(), request.teacherId(), request.parentId(),
			request.guardianRef(), request.historyCount(), request.latestRecordId(), request.historyJson(),
			request.executionId(), request.versionsJson(), request.createdAt().atOffset(ZoneOffset.UTC));
		if (!inserted.isEmpty()) return inserted.getFirst();
		return findCached(request.teacherId(), request.parentId(), request.historyCount(), request.latestRecordId())
			.orElseThrow(() -> new IllegalStateException("guardian label cache conflict could not be resolved"))
			.requestId();
	}

	public void insertSuggestion(NewSuggestion suggestion) {
		jdbcTemplate.update("""
			INSERT INTO guardian_label_suggestions (
			 id, request_id, teacher_id, parent_id, suggestion_id, axis, value,
			 confidence, evidence_quotes, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
			ON CONFLICT (request_id, suggestion_id) DO NOTHING
			""", suggestion.id(), suggestion.requestId(), suggestion.teacherId(), suggestion.parentId(),
			suggestion.suggestionId(), suggestion.axis().wireValue(), suggestion.value().wireValue(),
			suggestion.confidence(), suggestion.evidenceJson(), suggestion.createdAt().atOffset(ZoneOffset.UTC));
	}

	private List<StoredSuggestion> findSuggestions(UUID requestId) {
		return new ArrayList<>(jdbcTemplate.query("""
			SELECT suggestion.id, suggestion.suggestion_id, suggestion.axis, suggestion.value,
			       suggestion.confidence, suggestion.evidence_quotes::text
			FROM guardian_label_suggestions suggestion
			WHERE suggestion.request_id = ?
			  AND NOT EXISTS (
			    SELECT 1 FROM guardian_label_decisions decision
			    WHERE decision.suggestion_row_id = suggestion.id
			  )
			ORDER BY suggestion.axis
			""", (rs, row) -> new StoredSuggestion(
			(UUID) rs.getObject("id"),
			rs.getString("suggestion_id"),
			GuardianLabelAxis.fromWireValue(rs.getString("axis")),
			GuardianLabelValue.fromWireValue(rs.getString("value")),
			rs.getBigDecimal("confidence"),
			rs.getString("evidence_quotes")
		), requestId));
	}

	private record RequestRow(UUID id, String guardianRef, String executionId) {
	}

	public record CachedSuggestions(
		UUID requestId, String guardianRef, String executionId, List<StoredSuggestion> suggestions
	) {
	}

	public record StoredSuggestion(
		UUID suggestionRef, String suggestionId, GuardianLabelAxis axis, GuardianLabelValue value,
		BigDecimal confidence, String evidenceJson
	) {
		public StoredSuggestion(
			String suggestionId, GuardianLabelAxis axis, GuardianLabelValue value,
			BigDecimal confidence, String evidenceJson
		) {
			this(null, suggestionId, axis, value, confidence, evidenceJson);
		}
	}

	public record NewRequest(
		UUID id, UUID teacherId, UUID parentId, String guardianRef, int historyCount,
		String latestRecordId, String historyJson, String executionId, String versionsJson, Instant createdAt
	) {
	}

	public record NewSuggestion(
		UUID id, UUID requestId, UUID teacherId, UUID parentId, String suggestionId,
		GuardianLabelAxis axis, GuardianLabelValue value, BigDecimal confidence,
		String evidenceJson, Instant createdAt
	) {
	}
}
