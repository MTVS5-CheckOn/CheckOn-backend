package com.checkon.member.membership.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 초대 코드를 {@code member_invitation_codes.code_hash} 형식으로 바꾼다.
 *
 * <p>형식은 V38 의 {@code ck_member_invitation_codes_hash} = {@code ^sha256:[0-9a-f]{64}$} 다.
 * 비교는 <b>동등 비교</b>이며 평문은 어디에도 남기지 않는다 — 저장·로그·예외 메시지 전부.</p>
 *
 * <p>🔴 정규화 후 해싱한다. 대소문자나 앞뒤 공백 때문에 같은 코드가 다른 해시가 되면
 * 사용자는 "코드가 틀렸다"는 안내를 받는다.</p>
 */
public final class InvitationCodeHasher {

	private static final String HASH_PREFIX = "sha256:";
	private static final String DIGEST = "SHA-256";

	private InvitationCodeHasher() {
	}

	/** 앞뒤 공백 제거 + 대문자화. 내부 공백·하이픈은 보존한다 — 발급 형식을 모르기 때문이다. */
	public static String normalize(String raw) {
		return raw == null ? null : raw.strip().toUpperCase(Locale.ROOT);
	}

	public static String hash(String rawCode) {
		String normalized = normalize(rawCode);
		if (normalized == null || normalized.isEmpty()) {
			return null;
		}
		try {
			byte[] digest = MessageDigest.getInstance(DIGEST)
				.digest(normalized.getBytes(StandardCharsets.UTF_8));
			return HASH_PREFIX + HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 digest is unavailable", exception);
		}
	}
}
