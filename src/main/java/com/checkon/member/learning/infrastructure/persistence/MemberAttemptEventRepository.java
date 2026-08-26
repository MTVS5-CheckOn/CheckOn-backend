package com.checkon.member.learning.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.learning.domain.MemberAttemptEventType;

/**
 * {@code member_attempt_events} 기록. 학생 self select/insert 만 있다(V40:330-350) — 이벤트
 * 로그는 학생만 남기고 학부모·강사에게는 노출되지 않는다.
 *
 * <p>🔴 {@code client_sequence} 는 nullable — PostgreSQL 은 NULL 을 unique 로 보지 않으므로
 * {@code STARTED}/{@code SUBMITTED} 여러 행 방어는 이 인덱스가 아니라 {@code attempts.status}
 * CHECK 가 한다(V40:144-145 주석).</p>
 */
@Repository
public class MemberAttemptEventRepository {

	private static final String INSERT = """
		INSERT INTO member_attempt_events
			(id, attempt_id, event_type, item_id, client_sequence, occurred_at)
		VALUES (?, ?, ?, ?, ?, ?)
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberAttemptEventRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(
		UUID attemptId,
		MemberAttemptEventType type,
		UUID itemId,
		Integer clientSequence,
		Instant occurredAt
	) {
		jdbcTemplate.update(INSERT,
			UUID.randomUUID(),
			attemptId,
			type.name(),
			itemId,
			clientSequence,
			OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
	}
}
