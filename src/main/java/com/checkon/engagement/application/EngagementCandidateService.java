package com.checkon.engagement.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Detection이 검증·저장한 결과를 강사 검토 후보로 넘기는 공개 애플리케이션 경계다. */
@Service
public class EngagementCandidateService {
	private final JdbcTemplate jdbcTemplate;

	public EngagementCandidateService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void createPendingAlerts(UUID teacherId, UUID runId, Instant now) {
		// AI 결과는 강사의 판단을 돕는 제안이다. 따라서 Detection 완료 시점에는
		// 자동 승인하지 않고, 근거가 검증된 signal만 PENDING_REVIEW로 만든다.
		jdbcTemplate.update("""
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
			""", now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
			runId, teacherId);
	}
}
