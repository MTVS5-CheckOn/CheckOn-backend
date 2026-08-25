package com.checkon.member.auth.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 공개 학생 ID. 학부모가 자녀를 찾을 때, 학생이 로그인할 때 쓴다.
 *
 * <p>🔴 저장 형태는 {@code STU-XXXXXX} 다 — 접두 하이픈 1개를 유지한다.
 * 설계 정본 §9-3 과 V38 의 CHECK {@code ^STU-[A-Z0-9]{6,12}$} 가 같은 형태를 요구한다.
 * 설계는 "하이픈을 제거해서 저장하지 마라 — CHECK 와 어긋난다"고 명시한다.</p>
 *
 * <p>비교는 항상 정규화 후에 한다. 원본 문자열을 그대로 비교하지 않는다 —
 * {@code "stu b52d9k"} · {@code "STUB52D9K"} · {@code "stu-b52d9k"} 가 같은 값이다.</p>
 */
public final class PublicStudentId {

	public static final Pattern STORED_FORMAT = Pattern.compile("^STU-[A-Z0-9]{6,12}$");
	private static final String PREFIX = "STU";

	private PublicStudentId() {
	}

	/**
	 * 입력을 저장 형태로 정규화한다.
	 *
	 * <p>공백 제거 → 대문자화 → {@code STU} 접두 뒤의 구분자를 하이픈 1개로 고정.</p>
	 *
	 * @return 정규화 결과. 형식을 만족하지 못하면 {@code null} —
	 *         🔴 이 경우도 호출부는 "없음"과 같게 다룬다. 형식 오류를 따로 알려주면 열거 단서가 된다
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String compact = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		if (!compact.startsWith(PREFIX)) {
			return null;
		}
		String candidate = PREFIX + "-" + compact.substring(PREFIX.length());
		return STORED_FORMAT.matcher(candidate).matches() ? candidate : null;
	}
}
