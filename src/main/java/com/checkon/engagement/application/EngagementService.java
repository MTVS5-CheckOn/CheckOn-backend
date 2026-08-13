package com.checkon.engagement.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.engagement.domain.AlertStatus;
import com.checkon.engagement.domain.EngagementAlert;
import com.checkon.engagement.domain.Intervention;
import com.checkon.engagement.domain.InterventionReminder;
import com.checkon.engagement.domain.InterventionStatus;
import com.checkon.engagement.domain.ReminderStatus;
import com.checkon.engagement.infrastructure.persistence.EngagementAlertRepository;
import com.checkon.engagement.infrastructure.persistence.InterventionReminderRepository;
import com.checkon.engagement.infrastructure.persistence.InterventionRepository;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

@Service
public class EngagementService {
	private final EngagementAlertRepository alerts;
	private final InterventionRepository interventions;
	private final InterventionReminderRepository reminders;
	private final TeacherTenantDatabaseContext tenantContext;
	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public EngagementService(
		EngagementAlertRepository alerts,
		InterventionRepository interventions,
		InterventionReminderRepository reminders,
		TeacherTenantDatabaseContext tenantContext,
		JdbcTemplate jdbcTemplate,
		Clock clock
	) {
		this.alerts = alerts;
		this.interventions = interventions;
		this.reminders = reminders;
		this.tenantContext = tenantContext;
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<AlertView> list(UUID teacherId, AlertStatus status) {
		setTenantScope(teacherId);
		return alerts.findAllByTeacherIdAndStatusOrderByCreatedAtAscIdAsc(
			teacherId, status
		).stream().map(this::alertView).toList();
	}

	@Transactional(readOnly = true)
	public AlertDetail detail(UUID teacherId, UUID alertId) {
		setTenantScope(teacherId);
		EngagementAlert alert = requireAlert(alertId, teacherId);
		AlertMetadata metadata = jdbcTemplate.queryForObject("""
			SELECT personal.real_name AS student_name,
			       class_group.name AS class_name,
			       signal.rule_id,
			       signal.signal_type,
			       signal.display_label,
			       signal.brief_text,
			       signal.fallback_used
			FROM engagement_alerts engagement
			JOIN detection_signal_results signal
			  ON signal.id = engagement.detection_signal_result_id
			LEFT JOIN student_personal_information personal
			  ON personal.student_id = engagement.student_id
			LEFT JOIN class_groups class_group
			  ON class_group.teacher_id = engagement.teacher_id
			 AND signal.class_ref = 'cl_' || replace(class_group.id::text, '-', '')
			WHERE engagement.id = ? AND engagement.teacher_id = ?
			""", (resultSet, rowNumber) -> new AlertMetadata(
			resultSet.getString("student_name"),
			resultSet.getString("class_name"),
			resultSet.getString("rule_id"),
			resultSet.getString("signal_type"),
			resultSet.getString("display_label"),
			resultSet.getString("brief_text"),
			resultSet.getBoolean("fallback_used")
		), alertId, teacherId);
		List<EvidenceView> evidence = jdbcTemplate.query("""
			SELECT id, source_hint, record_id, summary, role, observed, sample_size, occurred_on
			FROM detection_result_evidence
			WHERE detection_signal_result_id = ?
			ORDER BY created_at, id
			""", (resultSet, rowNumber) -> new EvidenceView(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("source_hint"),
			resultSet.getString("record_id"),
			resultSet.getString("summary"),
			resultSet.getString("role"),
			resultSet.getBigDecimal("observed"),
			resultSet.getObject("sample_size", Integer.class),
			resultSet.getObject("occurred_on", LocalDate.class)
		), alert.detectionSignalResultId());
		return new AlertDetail(
			alert.id(), alert.studentId(), metadata.studentName(), metadata.className(),
			metadata.ruleId(), metadata.signalType(), metadata.displayLabel(), metadata.brief(),
			metadata.briefFallback(), alert.status(), alert.createdAt(), evidence
		);
	}

	@Transactional
	public AlertView approve(UUID teacherId, UUID alertId) {
		setTenantScope(teacherId);
		EngagementAlert alert = requireAlert(alertId, teacherId);
		try {
			alert.approve(Instant.now(clock));
		}
		catch (IllegalStateException exception) {
			throw invalidState();
		}
		completeOpenTodo(teacherId, alertId, Instant.now(clock));
		return alertView(alert);
	}

	@Transactional
	public AlertView reject(UUID teacherId, UUID alertId, String note) {
		setTenantScope(teacherId);
		EngagementAlert alert = requireAlert(alertId, teacherId);
		try {
			alert.reject(note, Instant.now(clock));
		}
		catch (IllegalArgumentException exception) {
			throw EngagementException.of(
				EngagementException.Reason.INVALID_REQUEST,
				"invalid rejection"
			);
		}
		catch (IllegalStateException exception) {
			throw invalidState();
		}
		completeOpenTodo(teacherId, alertId, Instant.now(clock));
		return alertView(alert);
	}

	private void completeOpenTodo(UUID teacherId, UUID alertId, Instant completedAt) {
		jdbcTemplate.update("""
			UPDATE alert_follow_up_todos
			SET status = 'DONE', completed_at = ?, updated_at = ?
			WHERE teacher_id = ? AND alert_id = ? AND status = 'OPEN'
		""", completedAt.atOffset(ZoneOffset.UTC), completedAt.atOffset(ZoneOffset.UTC),
			teacherId, alertId);
	}

	@Transactional
	public InterventionView createIntervention(
		UUID teacherId,
		UUID alertId,
		String type,
		String content
	) {
		setTenantScope(teacherId);
		EngagementAlert alert = requireAlert(alertId, teacherId);
		if (alert.status() != AlertStatus.APPROVED) {
			throw invalidState();
		}
		try {
			Instant now = Instant.now(clock);
			Intervention intervention = interventions.saveAndFlush(Intervention.create(
				teacherId, alert.studentId(), alert.id(), type, content, now
			));
			reminders.saveAndFlush(InterventionReminder.create(
				teacherId, intervention.id(), now.plusSeconds(7L * 24 * 60 * 60), now
			));
			return interventionView(intervention);
		}
		catch (IllegalArgumentException exception) {
			throw EngagementException.of(
				EngagementException.Reason.INVALID_REQUEST,
				"invalid intervention"
			);
		}
	}

	@Transactional
	public InterventionView finishIntervention(
		UUID teacherId,
		UUID interventionId,
		boolean complete
	) {
		setTenantScope(teacherId);
		Intervention intervention = interventions.findByIdAndTeacherId(
			interventionId, teacherId
		).orElseThrow(EngagementService::notFound);
		Instant now = Instant.now(clock);
		try {
			if (complete) {
				intervention.complete(now);
			}
			else {
				intervention.cancel(now);
				reminders.findByTeacherIdAndInterventionIdAndStatus(
					teacherId, interventionId, ReminderStatus.ACTIVE
				).ifPresent(reminder -> reminder.cancel(now));
			}
		}
		catch (IllegalStateException exception) {
			throw invalidState();
		}
		return interventionView(intervention);
	}

	@Transactional
	public ReminderView finishReminder(
		UUID teacherId,
		UUID reminderId,
		boolean complete
	) {
		setTenantScope(teacherId);
		InterventionReminder reminder = reminders.findByIdAndTeacherId(
			reminderId, teacherId
		).orElseThrow(EngagementService::notFound);
		try {
			if (complete) {
				reminder.complete(Instant.now(clock));
			}
			else {
				reminder.cancel(Instant.now(clock));
			}
		}
		catch (IllegalStateException exception) {
			throw invalidState();
		}
		return reminderView(reminder);
	}

	private void setTenantScope(UUID teacherId) {
		if (teacherId == null) {
			throw EngagementException.of(
				EngagementException.Reason.INVALID_PRINCIPAL,
				"teacher principal required"
			);
		}
		// set_config(..., true)는 트랜잭션 종료 시 자동 해제된다. 반드시 현재
		// @Transactional 메서드 안에서 설정해야 연결 풀의 다음 요청에 남지 않는다.
		tenantContext.setCurrentTeacher(teacherId);
	}

	private EngagementAlert requireAlert(UUID alertId, UUID teacherId) {
		// 다른 테넌트의 존재 여부를 노출하지 않도록 없음과 접근 불가를 같은 404로 처리한다.
		return alerts.findByIdAndTeacherId(alertId, teacherId)
			.orElseThrow(EngagementService::notFound);
	}

	private AlertView alertView(EngagementAlert alert) {
		return new AlertView(
			alert.id(), alert.studentId(), alert.detectionSignalResultId(),
			alert.status(), alert.decisionNote(), alert.decidedAt(), alert.createdAt()
		);
	}

	private InterventionView interventionView(Intervention intervention) {
		return new InterventionView(
			intervention.id(), intervention.alertId(), intervention.studentId(),
			intervention.type(), intervention.content(), intervention.status(),
			intervention.createdAt()
		);
	}

	private ReminderView reminderView(InterventionReminder reminder) {
		return new ReminderView(
			reminder.id(), reminder.interventionId(), reminder.scheduledAt(),
			reminder.status()
		);
	}

	private static EngagementException notFound() {
		return EngagementException.of(
			EngagementException.Reason.NOT_FOUND,
			"resource not found"
		);
	}

	private static EngagementException invalidState() {
		return EngagementException.of(
			EngagementException.Reason.INVALID_STATE,
			"invalid state transition"
		);
	}

	public record AlertView(
		UUID id,
		UUID studentId,
		UUID detectionSignalResultId,
		AlertStatus status,
		String decisionNote,
		Instant decidedAt,
		Instant createdAt
	) {
	}

	public record EvidenceView(
		UUID id,
		String sourceHint,
		String recordId,
		String summary,
		String role,
		BigDecimal observed,
		Integer sampleSize,
		LocalDate occurredOn
	) {
	}

	public record AlertDetail(
		UUID alertId,
		UUID studentId,
		String studentName,
		String className,
		String ruleId,
		String signalType,
		String displayLabel,
		String brief,
		boolean briefFallback,
		AlertStatus status,
		Instant createdAt,
		List<EvidenceView> evidence
	) {
	}

	private record AlertMetadata(
		String studentName,
		String className,
		String ruleId,
		String signalType,
		String displayLabel,
		String brief,
		boolean briefFallback
	) {
	}

	public record InterventionView(
		UUID id,
		UUID alertId,
		UUID studentId,
		String type,
		String content,
		InterventionStatus status,
		Instant createdAt
	) {
	}

	public record ReminderView(
		UUID id,
		UUID interventionId,
		Instant scheduledAt,
		ReminderStatus status
	) {
	}
}
