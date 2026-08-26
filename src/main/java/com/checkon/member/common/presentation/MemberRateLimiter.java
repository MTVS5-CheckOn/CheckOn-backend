package com.checkon.member.common.presentation;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;

/**
 * 공개 학생 ID · 초대 코드 열거를 늦추는 인프로세스 고정 창 리미터 (설계 §9-3).
 *
 * <p>🔴 <b>이 구현은 인스턴스 로컬이다.</b> 다중 인스턴스로 배포하면 실효 상한이
 * 인스턴스 수만큼 곱해진다 — 3대면 분당 30회다. 분산 레이트 리밋은 Redis 같은 공유 저장소가
 * 필요한데 {@code build.gradle} 이 무접촉이라 이 PR 에서 넣을 수 없다.
 * 한계를 숨기지 않고 {@code MB-15} 로 등재했다.</p>
 *
 * <p>🔴 계정과 IP <b>두 축</b>을 모두 센다. 계정만 세면 가입을 반복해 우회하고, IP 만 세면
 * 같은 회선의 정상 사용자가 함께 막힌다. 둘 중 하나라도 넘으면 거절한다.</p>
 */
@Component
public class MemberRateLimiter {

	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final MemberRateLimitProperties properties;
	private final Clock clock;

	/**
	 * 🔴 <b>테스트 전용 오버라이드</b>. 프로덕션은 {@code null} 이고 {@link #permits()} 는
	 * {@code properties.permits()} 를 그대로 돌려준다. 테스트에서 상한을 컨텍스트 프로퍼티로
	 * 갈라 두면 그 조합마다 스프링이 새 컨텍스트를 뜬다(G17) — 그걸 막으려고 여기서 런타임에
	 * 조정한다. 프로덕션 동작은 이 필드를 손대지 않아야 한다.
	 */
	private volatile Integer permitsOverride;

	public MemberRateLimiter(MemberRateLimitProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	private int permits() {
		Integer override = this.permitsOverride;
		return override != null ? override : properties.permits();
	}

	/**
	 * 🔴 <b>테스트 전용</b>. 상한을 런타임에 덮어써 컨텍스트를 새로 띄우지 않게 한다.
	 * {@link #resetForTesting()} 로 되돌린다.
	 */
	public void overridePermitsForTesting(int permits) {
		this.permitsOverride = permits;
	}

	/**
	 * 🔴 <b>테스트 전용</b>. 창 카운터를 비우고 오버라이드를 해제한다.
	 * 컨텍스트를 공유하는 테스트 클래스가 서로의 카운터·설정에 영향받지 않게 한다.
	 */
	public void resetForTesting() {
		windows.clear();
		this.permitsOverride = null;
	}

	/**
	 * 한 번의 호출을 기록하고 상한을 넘었으면 거절한다.
	 *
	 * @param clientIp 없으면 {@code null}. 🔴 없는 값을 {@code "unknown"} 으로 채우지 않는다 —
	 *                 그러면 IP 를 못 읽는 모든 요청이 한 버킷을 공유해 서로를 막는다
	 */
	public void check(String routeKey, UUID accountId, String clientIp) {
		Instant now = clock.instant();
		evictExpired(now);
		boolean accountExceeded = isExceeded(key(routeKey, "account", accountId.toString()), now);
		boolean addressExceeded = clientIp != null
			&& isExceeded(key(routeKey, "ip", clientIp), now);
		if (accountExceeded || addressExceeded) {
			throw new MemberException(MemberErrorCode.RATE_LIMITED,
				"rate limit exceeded for " + routeKey,
				new RateLimitDetails(properties.window().toSeconds()));
		}
	}

	private boolean isExceeded(String key, Instant now) {
		Window window = windows.compute(key, (ignored, existing) ->
			existing == null || existing.isExpired(now, properties.window())
				? new Window(now)
				: existing);
		return window.increment() > permits();
	}

	/**
	 * 🔴 상한을 넘으면 <b>만료된 창만</b> 비운다. 살아 있는 창까지 지우면 그 순간 모든 호출자의
	 * 카운터가 0 이 되어 리미터가 무력해진다 — 공격자가 맵을 부풀려 통과하는 경로가 생긴다.
	 */
	private void evictExpired(Instant now) {
		if (windows.size() <= properties.maximumTrackedKeys()) {
			return;
		}
		windows.values().removeIf(window -> window.isExpired(now, properties.window()));
	}

	private String key(String routeKey, String axis, String value) {
		return routeKey + '|' + axis + '|' + value;
	}

	/** 고정 창 하나. {@code startedAt} 이 창의 시작이고 카운트는 그 안에서만 유효하다. */
	private static final class Window {

		private final Instant startedAt;
		private final AtomicInteger count = new AtomicInteger();

		private Window(Instant startedAt) {
			this.startedAt = startedAt;
		}

		private boolean isExpired(Instant now, java.time.Duration length) {
			return !now.isBefore(startedAt.plus(length));
		}

		private int increment() {
			return count.incrementAndGet();
		}
	}
}
