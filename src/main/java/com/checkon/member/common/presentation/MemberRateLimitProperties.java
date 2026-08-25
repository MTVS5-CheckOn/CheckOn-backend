package com.checkon.member.common.presentation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 열거 방어용 레이트 리밋 임계값.
 *
 * <p>🔴 기본값을 <b>코드에</b> 둔다. {@code application*.yaml} 은 무접촉이라 거기 적을 수 없고,
 * 그렇다고 서비스에 숫자를 박으면 코드 규칙 §2 위반이다. 운영에서 조정이 필요하면
 * {@code CHECKON_MEMBER_RATE_LIMIT_*} 환경변수로 덮는다.</p>
 *
 * @param permits 창 하나가 허용하는 요청 수. 설계 정본 §9-3 이 분당 10회로 잡았다
 * @param window  고정 창 길이
 * @param maximumTrackedKeys 추적 상한. 🔴 넘으면 만료된 항목부터 비운다 —
 *                           메모리가 무한히 늘지 않게 하는 것이 목적이다
 */
@ConfigurationProperties(prefix = "checkon.member.rate-limit")
public record MemberRateLimitProperties(
	Integer permits,
	Duration window,
	Integer maximumTrackedKeys
) {

	private static final int DEFAULT_PERMITS = 10;
	private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
	private static final int DEFAULT_MAXIMUM_TRACKED_KEYS = 10_000;

	public MemberRateLimitProperties {
		permits = permits == null ? DEFAULT_PERMITS : permits;
		window = window == null ? DEFAULT_WINDOW : window;
		maximumTrackedKeys =
			maximumTrackedKeys == null ? DEFAULT_MAXIMUM_TRACKED_KEYS : maximumTrackedKeys;
	}
}
