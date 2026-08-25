package com.checkon.member.common.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code Idempotency-Key} 를 받는 오퍼레이션의 공통 처리 (설계 §12-1 · 분기표 §0-4).
 *
 * <p>한 트랜잭션 안의 순서:</p>
 * <pre>
 * 1. 자문 잠금으로 같은 (account, route, key) 를 직렬화한다
 * 2. 저장된 기록 조회
 *      있고 hash 같음  → 저장된 status + body 를 그대로 재생 (본 처리 실행 안 함)
 *      있고 hash 다름  → 409 IDEMPOTENCY_CONFLICT
 * 3. 없음 → 본 처리 실행 → 같은 트랜잭션에서 INSERT
 * </pre>
 *
 * <p>🔴 <b>왜 자문 잠금인가 — 지시서의 "INSERT 가 23505 면 재조회해서 재생"은 한 트랜잭션 안에서
 * 성립하지 않는다.</b> PostgreSQL 은 제약 위반이 나는 순간 트랜잭션을 abort 상태로 만들고,
 * 그 뒤의 모든 문장을 {@code current transaction is aborted} 로 거절한다 — 되돌아가 재조회할
 * 커넥션이 남아 있지 않다. 자리를 먼저 잡고 나중에 응답으로 채우는 2단계도 불가능하다:
 * {@code member_idempotency_records} 에는 <b>UPDATE 정책이 없고</b>(V38) PR4 는 마이그레이션이
 * 0개다. 그래서 충돌을 <b>사후에 수습하지 않고 사전에 없앤다</b> — 두 번째 요청은 1번에서 막혀
 * 첫 번째가 커밋한 뒤에야 2번을 읽고, 그때는 기록이 보인다. 본 처리는 한 번만 돈다.</p>
 *
 * <p>🔴 요청 해시는 <b>수신한 원문 바이트</b>로 만든다. 역직렬화 후 다시 직렬화하면 필드 순서·
 * 공백에 따라 값이 흔들려 같은 요청이 충돌로 판정된다.</p>
 */
@Component
public class IdempotencyGuard {

	/** 설계 정본 §12-1. 보관 24시간. */
	private static final Duration RETENTION = Duration.ofHours(24);

	private static final String HASH_PREFIX = "sha256:";
	private static final String DIGEST = "SHA-256";

	private final MemberIdempotencyRepository idempotencyRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public IdempotencyGuard(
		MemberIdempotencyRepository idempotencyRepository,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.idempotencyRepository = idempotencyRepository;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * @param routeKey  {@code "<METHOD> <템플릿 경로>"}. 🔴 실제 id 가 박힌 경로가 아니다 —
	 *                  그러면 같은 오퍼레이션이 요청마다 다른 key 공간을 쓴다
	 * @param rawBody   수신한 요청 본문 원문
	 * @param action    본 처리. 재생일 때는 <b>부르지 않는다</b>. 상태 코드도 여기서 정한다
	 */
	public IdempotentOutcome execute(
		UUID accountId,
		String routeKey,
		String idempotencyKey,
		String rawBody,
		Supplier<IdempotentPayload> action
	) {
		idempotencyRepository.lock(accountId, routeKey, idempotencyKey);

		String requestHash = hash(rawBody);
		Optional<MemberIdempotencyRecord> stored =
			idempotencyRepository.find(accountId, routeKey, idempotencyKey);
		if (stored.isPresent()) {
			MemberIdempotencyRecord record = stored.get();
			if (!record.requestHash().equals(requestHash)) {
				throw new MemberException(MemberErrorCode.IDEMPOTENCY_CONFLICT,
					"same idempotency key was used with a different request body");
			}
			return new IdempotentOutcome(record.responseStatus(), record.responseBody(), true);
		}

		IdempotentPayload payload = action.get();
		String body = objectMapper.writeValueAsString(payload.body());
		Instant now = clock.instant();
		// 🔴 저장된 값을 그대로 응답으로 쓴다. jsonb 가 키 순서를 정규화하므로, 여기서 만든
		//    문자열을 내려보내면 재생 응답과 바이트가 달라진다.
		String normalized = idempotencyRepository.insert(accountId, routeKey, idempotencyKey,
			requestHash, payload.status(), body, now, now.plus(RETENTION));
		return new IdempotentOutcome(payload.status(), normalized, false);
	}

	/** {@code sha256:<64hex>}. V38 의 {@code ck_member_idempotency_hash} 형식이다. */
	public String hash(String rawBody) {
		String payload = rawBody == null ? "" : rawBody;
		try {
			byte[] digest = MessageDigest.getInstance(DIGEST)
				.digest(payload.getBytes(StandardCharsets.UTF_8));
			return HASH_PREFIX + HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			// SHA-256 은 모든 JRE 가 반드시 제공한다. 여기 오면 런타임이 깨진 것이다.
			throw new IllegalStateException("SHA-256 digest is unavailable", exception);
		}
	}
}
