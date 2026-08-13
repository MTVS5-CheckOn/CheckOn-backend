package com.checkon.detection.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class DetectionRunQueryService {

	private final DetectionRunRepository runRepository;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;

	public DetectionRunQueryService(
		DetectionRunRepository runRepository,
		TeacherTenantDatabaseContext tenantContext,
		ObjectMapper objectMapper
	) {
		this.runRepository = runRepository;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true)
	public DetectionRunView find(UUID teacherId, UUID runId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(runId, "runId must not be null");
		tenantContext.setCurrentTeacher(teacherId);
		DetectionRun run = runRepository.findByIdAndTeacherId(runId, teacherId)
			.orElseThrow(DetectionExecutionException::runNotFound);
		return toView(run);
	}

	@Transactional(readOnly = true)
	public DetectionRunView findLatest(UUID teacherId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		tenantContext.setCurrentTeacher(teacherId);
		DetectionRun run = runRepository
			.findFirstByTeacherIdOrderByPreparedAtDescIdDesc(teacherId)
			.orElseThrow(DetectionExecutionException::runNotFound);
		return toView(run);
	}

	private DetectionRunView toView(DetectionRun run) {
		return new DetectionRunView(
			run.id(),
			run.status(),
			run.analysisDate(),
			run.attempts().size(),
			run.errorCode(),
			readStats(run.responseStatsPayload())
		);
	}

	private DetectionRunStatsView readStats(String statsPayload) {
		if (statsPayload == null) {
			return null;
		}
		try {
			JsonNode stats = objectMapper.readTree(statsPayload);
			List<SkippedRuleView> skippedRules = new ArrayList<>();
			stats.path("rules_skipped").forEach(rule -> skippedRules.add(new SkippedRuleView(
				rule.path("rule_id").asText(),
				rule.path("reason").asText(),
				rule.path("students").asInt()
			)));
			return new DetectionRunStatsView(
				stats.path("students_evaluated").asInt(),
				stats.path("signals_raised").asInt(),
				stats.path("excluded_under_2w").asInt(),
				stats.path("capped_out").asInt(),
				List.copyOf(skippedRules),
				nullableDecimal(stats, "r1_threshold_pp"),
				nullableText(stats, "r1_threshold_source"),
				nullableInteger(stats, "r1_pool_n")
			);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Stored detection stats are invalid", exception);
		}
	}

	private BigDecimal nullableDecimal(JsonNode parent, String field) {
		JsonNode value = parent.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.decimalValue();
	}

	private String nullableText(JsonNode parent, String field) {
		JsonNode value = parent.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asText();
	}

	private Integer nullableInteger(JsonNode parent, String field) {
		JsonNode value = parent.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asInt();
	}

	public record DetectionRunView(
		UUID runId,
		DetectionRunStatus status,
		LocalDate analysisDate,
		int attemptCount,
		String errorCode,
		DetectionRunStatsView stats
	) {
	}

	public record DetectionRunStatsView(
		int studentsEvaluated,
		int signalsRaised,
		int excludedUnderTwoWeeks,
		int cappedOut,
		List<SkippedRuleView> rulesSkipped,
		BigDecimal r1ThresholdPp,
		String r1ThresholdSource,
		Integer r1PoolN
	) {
	}

	public record SkippedRuleView(String ruleId, String reason, int students) {
	}
}
