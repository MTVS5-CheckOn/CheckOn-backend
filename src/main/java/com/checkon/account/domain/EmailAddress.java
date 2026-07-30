package com.checkon.account.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 로그인 식별자로 사용하는 이메일의 정규화와 최소 형식 검사를 담당하는 값 객체다.
 *
 * <p>이 객체를 통과한 이메일만 Account에 저장하게 하여 Controller나 Service마다
 * 서로 다른 정규화 규칙을 적용하는 실수를 방지한다.</p>
 */
public final class EmailAddress {

	private static final int MAX_LENGTH = 320;
	private static final Pattern BASIC_EMAIL_PATTERN =
		Pattern.compile("^[^\\s@]+@[^\\s@]+$");

	private final String value;

	private EmailAddress(String value) {
		this.value = value;
	}

	public static EmailAddress of(String rawEmail) {
		if (rawEmail == null) {
			throw new IllegalArgumentException("email must not be null");
		}
		// 로그인에서는 대소문자를 구분하지 않으므로 저장과 중복 조회 전에
		// 운영체제의 언어 설정에 영향받지 않는 형태로 정규화한다.
		String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("email must not be blank");
		}
		if (normalized.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("email must not exceed 320 characters");
		}
		// 여기서는 명백히 잘못된 구조만 거절한다. 실제 소유 여부 확인은
		// 향후 이메일 인증 기능의 책임이므로 복잡한 표준 정규식을 사용하지 않는다.
		if (!BASIC_EMAIL_PATTERN.matcher(normalized).matches()) {
			throw new IllegalArgumentException("email format is invalid");
		}
		return new EmailAddress(normalized);
	}

	public String value() {
		return value;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		return other instanceof EmailAddress emailAddress
			&& value.equals(emailAddress.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}
}
