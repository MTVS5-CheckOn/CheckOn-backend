package com.checkon.engagement.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Detection이 검증·저장한 결과를 강사 검토 후보로 넘기는 공개 애플리케이션 경계다. */
@Service
public class EngagementCandidateService {
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final String TODO_TEXT = "확인이 필요한 경보를 검토하고 상담 여부를 결정해 주세요.";
	private final JdbcTemplate jdbcTemplate;

	public EngagementCandidateService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void createPendingAlerts(UUID teacherId, UUID runId, Instant now) {
		// AI 결과는 강사의 판단을 돕는 제안이다. 따라서 Detection 완료 시점에는
		// 자동 승인하지 않고, 근거가 검증된 signal만 PENDING_REVIEW로 만든다.
		LocalDate dueDate = now.atZone(SERVICE_ZONE).toLocalDate();
		jdbcTemplate.update("""
			WITH created_alerts AS (
			INSERT INTO engagement_alerts (
			    teacher_id, student_id, detection_signal_result_id,
			    status, created_at, updated_at
			)
			SELECT run.teacher_id, alias.student_id, signal.id,
			       'PENDING_REVIEW', ?, ?
			FROM detection_signal_results signal
			JOIN detection_runs run ON run.id = signal.detection_run_id
			JOIN ai_student_aliases alias
			  ON alias.teacher_id = run.teacher_id
			 AND alias.alias = signal.student_ref
			WHERE run.id = ? AND run.teacher_id = ?
			  AND EXISTS (
			      SELECT 1
			      FROM detection_result_evidence evidence
			      WHERE evidence.detection_signal_result_id = signal.id
			  )
			ON CONFLICT (detection_signal_result_id) DO NOTHING
			RETURNING id, teacher_id, created_at
			)
			INSERT INTO alert_follow_up_todos (
			    teacher_id, kind, alert_id, text, due_date, status,
			    completed_at, created_at, updated_at
			)
			SELECT teacher_id, 'ALERT_FOLLOW_UP', id, ?, ?, 'OPEN', NULL,
			       created_at, created_at
			FROM created_alerts
			ON CONFLICT (alert_id, kind) DO NOTHING
			""", now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
			runId, teacherId, TODO_TEXT, dueDate);
	}
}
