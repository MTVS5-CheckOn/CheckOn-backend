package com.checkon.counsel.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest.Action;

@Repository
public class GuardianLabelDecisionRepository {

	private final JdbcTemplate jdbcTemplate;

	public GuardianLabelDecisionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<Suggestion> findSuggestion(UUID teacherId, UUID parentId, UUID suggestionRef) {
		return jdbcTemplate.query("""
			SELECT id, suggestion_id, axis, value
			FROM guardian_label_suggestions
			WHERE id = ? AND teacher_id = ? AND parent_id = ?
			""", (rs, row) -> new Suggestion(
			(UUID) rs.getObject("id"), rs.getString("suggestion_id"),
			GuardianLabelAxis.fromWireValue(rs.getString("axis")),
			GuardianLabelValue.fromWireValue(rs.getString("value"))
		), suggestionRef, teacherId, parentId).stream().findFirst();
	}

	public Optional<Decision> findDecision(UUID suggestionRef) {
		return jdbcTemplate.query("""
			SELECT action, decided_value FROM guardian_label_decisions WHERE suggestion_row_id = ?
			""", (rs, row) -> new Decision(
			Action.valueOf(rs.getString("action")),
			rs.getString("decided_value") == null ? null : GuardianLabelValue.fromWireValue(rs.getString("decided_value"))
		), suggestionRef).stream().findFirst();
	}

	public boolean insertDecision(
		UUID id, Suggestion suggestion, UUID teacherId, UUID parentId,
		Action action, GuardianLabelValue decidedValue, Instant createdAt
	) {
		return jdbcTemplate.update("""
			INSERT INTO guardian_label_decisions (
			 id, suggestion_row_id, teacher_id, parent_id, suggestion_id, axis, action, decided_value, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (suggestion_row_id) DO NOTHING
			""", id, suggestion.ref(), teacherId, parentId, suggestion.suggestionId(), suggestion.axis().wireValue(), action.name(),
			decidedValue == null ? null : decidedValue.wireValue(), createdAt.atOffset(ZoneOffset.UTC)) == 1;
	}

	public void upsertCurrentLabel(
		UUID teacherId, UUID parentId, GuardianLabelAxis axis, GuardianLabelValue value,
		String suggestionId, Instant now
	) {
		jdbcTemplate.update("""
			INSERT INTO guardian_labels (
			 id, teacher_id, parent_id, axis, value, source_suggestion_id, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (teacher_id, parent_id, axis) DO UPDATE
			SET value = EXCLUDED.value,
			    source_suggestion_id = EXCLUDED.source_suggestion_id,
			    updated_at = EXCLUDED.updated_at
			""", UUID.randomUUID(), teacherId, parentId, axis.wireValue(), value.wireValue(), suggestionId,
			now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
	}

	public List<CurrentLabel> findCurrentLabels(UUID teacherId, UUID parentId) {
		return jdbcTemplate.query("""
			SELECT axis, value, updated_at FROM guardian_labels
			WHERE teacher_id = ? AND parent_id = ? ORDER BY axis
			""", (rs, row) -> new CurrentLabel(
			GuardianLabelAxis.fromWireValue(rs.getString("axis")),
			GuardianLabelValue.fromWireValue(rs.getString("value")),
			rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()
		), teacherId, parentId);
	}

	public record Suggestion(UUID ref, String suggestionId, GuardianLabelAxis axis, GuardianLabelValue value) {
	}

	public record Decision(Action action, GuardianLabelValue value) {
	}

	public record CurrentLabel(GuardianLabelAxis axis, GuardianLabelValue value, Instant updatedAt) {
	}
}
