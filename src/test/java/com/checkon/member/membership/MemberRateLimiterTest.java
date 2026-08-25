package com.checkon.member.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.presentation.MemberRateLimitProperties;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.common.presentation.RateLimitDetails;

/** 🔴 시간을 고정한 Clock 으로 센다. 실제 시계로 재면 창 경계에서 flaky 가 된다. */
class MemberRateLimiterTest {

	private static final String ROUTE = "POST /member/parents/me/children/verification";
	private static final Instant START = Instant.parse("2026-08-26T00:00:00Z");

	private final MutableClock clock = new MutableClock(START);
	private final MemberRateLimitProperties properties =
		new MemberRateLimitProperties(3, Duration.ofMinutes(1), 100);
	private final MemberRateLimiter limiter = new MemberRateLimiter(properties, clock);

	@Test
	@DisplayName("상한까지는 통과하고 상한 + 1 회째에 429 가 난다")
	void rejectsOncePermitsAreExhausted() {
		UUID account = UUID.randomUUID();
		for (int attempt = 0; attempt < 3; attempt++) {
			int index = attempt;
			assertThatCode(() -> limiter.check(ROUTE, account, "10.0.0.1"))
				.as("%d 번째 호출은 통과해야 한다", index + 1)
				.doesNotThrowAnyException();
		}
		assertThatThrownBy(() -> limiter.check(ROUTE, account, "10.0.0.1"))
			.isInstanceOf(MemberException.class)
			.extracting(exception -> ((MemberException) exception).errorCode())
			.isEqualTo(MemberErrorCode.RATE_LIMITED);
	}

	@Test
	@DisplayName("🔴 429 는 Retry-After 로 옮길 초를 details 에 담는다")
	void carriesRetryAfterSeconds() {
		UUID account = UUID.randomUUID();
		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.check(ROUTE, account, "10.0.0.1");
		}
		MemberException exception = catchMemberException(account, "10.0.0.1");
		assertThat(exception.details()).isEqualTo(new RateLimitDetails(60));
	}

	@Test
	@DisplayName("창이 지나면 다시 통과한다 — 영구 차단이 아니다")
	void recoversAfterWindow() {
		UUID account = UUID.randomUUID();
		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.check(ROUTE, account, "10.0.0.1");
		}
		clock.advance(Duration.ofMinutes(1));
		assertThatCode(() -> limiter.check(ROUTE, account, "10.0.0.1"))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("🔴 IP 축도 센다 — 계정만 세면 가입을 반복해 우회한다")
	void countsClientAddressSeparately() {
		String sharedAddress = "10.0.0.9";
		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.check(ROUTE, UUID.randomUUID(), sharedAddress);
		}
		// 계정은 매번 새것이라 계정 축은 1회씩이다. 그래도 IP 축이 상한을 넘었다.
		assertThatThrownBy(() -> limiter.check(ROUTE, UUID.randomUUID(), sharedAddress))
			.isInstanceOf(MemberException.class);
	}

	@Test
	@DisplayName("다른 경로는 서로의 상한을 잡아먹지 않는다")
	void separatesRoutes() {
		UUID account = UUID.randomUUID();
		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.check(ROUTE, account, "10.0.0.1");
		}
		assertThatCode(() -> limiter.check("POST /member/parents/me/children", account, "10.0.0.1"))
			.doesNotThrowAnyException();
	}

	private MemberException catchMemberException(UUID account, String address) {
		try {
			limiter.check(ROUTE, account, address);
			throw new AssertionError("rate limit was expected to trigger");
		}
		catch (MemberException exception) {
			return exception;
		}
	}

	/** 테스트 전용 이동 가능한 시계. */
	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration amount) {
			now = now.plus(amount);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
