package com.checkon.member.membership.application;

/**
 * 자녀 사전 확인이 돌려주는 이름을 부분 마스킹한다.
 *
 * <p>🔴 <b>어떤 입력에서도 원본을 그대로 돌려주지 않는다.</b> 두 글자 이름을 그냥 통과시키는
 * 구현이 흔한 실패다 — 「가운데가 없으니 가릴 것이 없다」로 보이지만, 이 응답은 <b>공개 학생 ID
 * 하나만 알면</b> 누구나 받을 수 있다. 가리지 않으면 ID 열거로 이름을 수집할 수 있다.</p>
 *
 * <p>규칙: 첫 글자만 남기고 나머지를 {@code *} 로 바꾼다. 단 세 글자 이상이면 마지막 글자를
 * 남겨 「김*수」 형태를 만든다 — 계약 예시가 그 모양이다.</p>
 */
public final class NameMasker {

	private static final char MASK = '*';

	private NameMasker() {
	}

	/**
	 * @param raw 원본 이름. {@code null} 이거나 공백뿐이면 {@code null} —
	 *            🔴 빈 문자열로 채우지 않는다(코드 규칙 §4)
	 */
	public static String mask(String raw) {
		if (raw == null) {
			return null;
		}
		// 공백은 마스킹 전에 접는다. "김 수" 와 "김수" 가 다른 모양으로 새어나가지 않게 한다.
		String name = raw.strip().replaceAll("\\s+", "");
		if (name.isEmpty()) {
			return null;
		}
		// 🔴 코드 포인트로 센다. 서로게이트 쌍을 char 로 자르면 깨진 문자가 나간다.
		int[] points = name.codePoints().toArray();
		if (points.length == 1) {
			return String.valueOf(MASK);
		}
		StringBuilder masked = new StringBuilder().appendCodePoint(points[0]);
		int lastVisible = points.length >= 3 ? points.length - 1 : points.length;
		masked.append(String.valueOf(MASK).repeat(lastVisible - 1));
		if (points.length >= 3) {
			masked.appendCodePoint(points[points.length - 1]);
		}
		return masked.toString();
	}
}
