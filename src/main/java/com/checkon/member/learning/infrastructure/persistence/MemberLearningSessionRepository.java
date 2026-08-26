package com.checkon.member.learning.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.learning.domain.MemberLearningSession;

/**
 * {@code member_learning_sessions} 저장. 정책은 학생 self, 학부모 scope, 강사 scope 세 방향
 * (V40:352-378) — 요약 데이터라 학부모·강사도 읽는다.
 *
 * <p>제출 트랜잭션 마지막에 한 행 INSERT 로 끝난다(§7 7단계). 이 리포지토리는 update 를 제공하지
 * 않는다 — 요약은 채점 완료 시점에 결정되고 이후 바뀌지 않는다.</p>
 */
@Repository
public class MemberLearningSessionRepository {

	private static final String INSERT = """
		INSERT INTO member_learning_sessions
			(id, attempt_id, student_id, teacher_id, assignment_id, title_text,
			 item_count, correct_count, active_elapsed_sec, submit_record_id, occurred_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberLearningSessionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(MemberLearningSession session) {
		jdbcTemplate.update(INSERT,
			session.id(),
			session.attemptId(),
			session.studentId(),
			session.teacherId(),
			session.assignmentId(),
			session.titleText(),
			session.itemCount(),
			session.correctCount(),
			session.activeElapsedSec(),
			session.submitRecordId(),
			OffsetDateTime.ofInstant(session.occurredAt(), ZoneOffset.UTC));
	}
}
