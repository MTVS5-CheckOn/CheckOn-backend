package com.checkon.member.auth.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code member_student_public_ids} 접근.
 *
 * <p>🔴 이 테이블에는 RLS 가 없다. 조회 주체가 소유자가 아니기 때문이다 — 학부모가 자녀의
 * 공개 ID 를 찾는다(MB-30). 대신 애플리케이션이 정규화 비교와 rate limit 으로 막는다.</p>
 */
@Repository
public class PublicStudentIdRepository {

	private static final String INSERT = """
		INSERT INTO member_student_public_ids (student_id, public_id, issued_at)
		VALUES (?, ?, ?)
		""";

	private static final String FIND_BY_STUDENT = """
		SELECT public_id FROM member_student_public_ids WHERE student_id = ?
		""";

	private static final String FIND_STUDENT_BY_PUBLIC_ID = """
		SELECT student_id FROM member_student_public_ids WHERE public_id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public PublicStudentIdRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(UUID studentProfileId, String publicId, Instant now) {
		jdbcTemplate.update(INSERT, studentProfileId, publicId,
			OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
	}

	public Optional<String> findByStudent(UUID studentProfileId) {
		return jdbcTemplate.query(FIND_BY_STUDENT, rs -> rs.next()
			? Optional.of(rs.getString(1))
			: Optional.<String>empty(), studentProfileId);
	}

	/** @param normalizedPublicId 🔴 반드시 정규화된 값. 원본 문자열로 조회하지 마라 */
	public Optional<UUID> findStudentByPublicId(String normalizedPublicId) {
		return jdbcTemplate.query(FIND_STUDENT_BY_PUBLIC_ID, rs -> rs.next()
			? Optional.ofNullable(rs.getObject(1, UUID.class))
			: Optional.<UUID>empty(), normalizedPublicId);
	}
}
