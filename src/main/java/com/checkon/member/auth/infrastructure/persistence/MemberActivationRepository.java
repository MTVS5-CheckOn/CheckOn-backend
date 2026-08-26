package com.checkon.member.auth.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.checkon.member.auth.domain.MemberActivationStatus;

/** {@code member_student_activation} 접근. RLS 정책이 주체별 격리를 담당한다. */
@Repository
public class MemberActivationRepository {

	private static final String INSERT = """
		INSERT INTO member_student_activation
		    (student_id, status, activated_at, created_at, updated_at)
		VALUES (?, ?, NULL, ?, ?)
		""";

	private static final String FIND_STATUS = """
		SELECT status FROM member_student_activation WHERE student_id = ?
		""";

	private static final String FIND_ACTIVATED_AT = """
		SELECT activated_at FROM member_student_activation WHERE student_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberActivationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insertPending(UUID studentProfileId, Instant now) {
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.update(INSERT, studentProfileId,
			MemberActivationStatus.PENDING_PARENT_LINK.name(), at, at);
	}

	/**
	 * 🔴 행이 없으면 빈 값을 그대로 돌려준다. PENDING_PARENT_LINK 로 승격하지 않는다 —
	 * 가입 트랜잭션이 깨진 흔적을 지우는 것이다. 판정은 guard 가 fail-closed 로 한다.
	 */
	public Optional<MemberActivationStatus> findStatus(UUID studentProfileId) {
		return jdbcTemplate.query(FIND_STATUS, rs -> rs.next()
			? Optional.of(MemberActivationStatus.valueOf(rs.getString(1)))
			: Optional.<MemberActivationStatus>empty(), studentProfileId);
	}

	public Optional<Instant> findActivatedAt(UUID studentProfileId) {
		return jdbcTemplate.query(FIND_ACTIVATED_AT, rs -> {
			if (!rs.next()) {
				return Optional.<Instant>empty();
			}
			OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
			return Optional.ofNullable(value).map(OffsetDateTime::toInstant);
		}, studentProfileId);
	}
}
