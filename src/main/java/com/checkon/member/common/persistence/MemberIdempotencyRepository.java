package com.checkon.member.common.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code member_idempotency_records} 접근.
 *
 * <p>🔴 이 테이블에는 INSERT · SELECT 정책만 있다(V38). <b>UPDATE 정책이 없다</b> —
 * 그래서 「자리를 먼저 잡고 나중에 응답으로 채운다」는 2단계 구현이 불가능하다. 응답이 확정된
 * 뒤에 한 번만 INSERT 한다. 동시 요청의 직렬화는 {@link IdempotencyGuard} 의 자문 잠금이 맡는다.</p>
 */
@Repository
public class MemberIdempotencyRepository {

	private static final String FIND = """
		SELECT request_hash, response_status, response_body
		FROM member_idempotency_records
		WHERE account_id = ? AND route_key = ? AND idempotency_key = ?
		""";

	/**
	 * 🔴 본문은 {@code ?::jsonb} 로 캐스팅하고 <b>저장된 값을 되돌려 받는다</b>.
	 *
	 * <p>{@code jsonb} 는 키 순서와 공백을 정규화한다. 그래서 최초 응답을 애플리케이션이 만든
	 * 문자열로 내려보내면, 재생 때 읽어온 문자열과 <b>바이트가 달라진다</b>(실측: 키 순서가 바뀐다).
	 * 최초 응답도 저장된 값으로 내려보내 「같은 key 는 같은 응답」을 바이트 수준에서 참으로 만든다.
	 * {@code RETURNING} 이라 왕복이 늘지 않는다.</p>
	 */
	private static final String INSERT = """
		INSERT INTO member_idempotency_records
		    (account_id, route_key, idempotency_key, request_hash,
		     response_status, response_body, created_at, expires_at)
		VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
		RETURNING response_body::text
		""";

	/**
	 * 🔴 같은 (account, route, key) 를 트랜잭션 단위로 직렬화한다. 잠금은 커밋·롤백에서
	 * 자동 해제된다. 이것이 없으면 두 요청이 둘 다 "저장된 것 없음"을 보고 본 처리를 두 번 한다.
	 */
	private static final String LOCK = """
		SELECT pg_advisory_xact_lock(hashtextextended(? || '|' || ? || '|' || ?, 0))
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberIdempotencyRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void lock(UUID accountId, String routeKey, String idempotencyKey) {
		// 반환값(void)은 쓰지 않는다. 잠금 획득 자체가 목적이다.
		jdbcTemplate.query(LOCK, resultSet -> null,
			accountId.toString(), routeKey, idempotencyKey);
	}

	public Optional<MemberIdempotencyRecord> find(
		UUID accountId,
		String routeKey,
		String idempotencyKey
	) {
		return jdbcTemplate.query(FIND, rs -> rs.next()
			? Optional.of(new MemberIdempotencyRecord(
				rs.getString(1), rs.getInt(2), rs.getString(3)))
			: Optional.<MemberIdempotencyRecord>empty(),
			accountId, routeKey, idempotencyKey);
	}

	/** @return 정규화되어 실제로 저장된 본문. 호출부는 이 값을 응답으로 쓴다 */
	public String insert(
		UUID accountId,
		String routeKey,
		String idempotencyKey,
		String requestHash,
		int responseStatus,
		String responseBody,
		Instant now,
		Instant expiresAt
	) {
		return jdbcTemplate.queryForObject(INSERT, String.class,
			accountId, routeKey, idempotencyKey, requestHash,
			responseStatus, responseBody,
			OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
			OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
	}
}
