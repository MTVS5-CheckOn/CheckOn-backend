package com.checkon.member.report.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 공개 다운로드 경로({@code GET /api/v1/member/files/reports/{token}})의 토큰 발급·검증.
 *
 * <p>🔴 <b>이 경로에는 세션이 없다.</b> PDF 뷰어는 {@code Authorization} 헤더를 못 붙이므로
 * {@code MemberSecurityConfiguration} 에서 {@code permitAll} 이다. 보안은 전적으로
 * <b>HMAC + 짧은 TTL + 다운로드 시점 관계 재검증</b> 셋에 있다.</p>
 *
 * <p>토큰 = {@code base64url(payload) + "." + base64url(HMAC-SHA256(payload, secret))},
 * payload = {@code v1:<reportFileId>:<parentProfileId>:<expEpochSec>:<nonce>}.</p>
 *
 * <p>🔴 <b>추측·열거가 불가능하다</b> — nonce 가 {@link SecureRandom} 128비트이고, 무엇보다
 * 서명이 없으면 어떤 payload 도 통과하지 못한다. 순번이나 짧은 난수를 쓰지 않는다.</p>
 *
 * <p>🔴 <b>서명 비교는 {@link MessageDigest#isEqual} 로 한다.</b> {@code String.equals} 는
 * 첫 다른 바이트에서 조기 반환하므로 응답 시간으로 서명을 한 바이트씩 맞춰 갈 수 있다.</p>
 *
 * <p>🔴 <b>만료 판정은 서버가 한다.</b> {@code expiresAt} 은 토큰 안에 있지만 서명으로 묶여
 * 있어 클라이언트가 못 바꾸고, 비교 기준 시각은 주입된 {@code Clock} 이다. 요청이 준 시각을
 * 쓰는 분기는 없다.</p>
 *
 * <p>🔴 검증 실패 사유를 구분해서 돌려주지 않는다 — {@link Optional#empty()} 하나다.
 * 만료·위조·형식오류를 구분하면 토큰 유효성을 탐색당한다(분기표 §4).</p>
 *
 * <p>⚠ 유출된 URL 은 TTL 동안 유효하다. signed URL 의 성질이지 버그가 아니다 — TTL 을 짧게
 * 두고 보존기간·공유 링크 정책은 MB-10 이다.</p>
 */
public class ReportFileTokenCodec {

	private static final String VERSION = "v1";
	private static final String ALGORITHM = "HmacSHA256";
	private static final String SEPARATOR = ".";
	private static final int PAYLOAD_FIELDS = 5;
	private static final int NONCE_BYTES = 16;

	private final byte[] secret;
	private final SecureRandom random = new SecureRandom();
	private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
	private final Base64.Decoder decoder = Base64.getUrlDecoder();

	public ReportFileTokenCodec(String signingSecret) {
		this.secret = signingSecret.getBytes(StandardCharsets.UTF_8);
	}

	/** 새 nonce 를 뽑아 토큰을 만든다. 같은 인자로 두 번 부르면 다른 토큰이 나온다. */
	public String issue(UUID reportFileId, UUID parentProfileId, Instant expiresAt) {
		byte[] bytes = new byte[NONCE_BYTES];
		random.nextBytes(bytes);
		String nonce = HexFormat.of().formatHex(bytes);
		return encode(new ReportFileToken(reportFileId, parentProfileId, expiresAt, nonce));
	}

	String encode(ReportFileToken token) {
		String payload = VERSION + ":" + token.reportFileId() + ":" + token.parentProfileId()
			+ ":" + token.expiresAt().getEpochSecond() + ":" + token.nonce();
		byte[] raw = payload.getBytes(StandardCharsets.UTF_8);
		return encoder.encodeToString(raw) + SEPARATOR + encoder.encodeToString(sign(raw));
	}

	/**
	 * 서명 → 만료 순으로 본다. 어느 단계에서 걸리든 결과는 같다.
	 *
	 * @param now 주입된 {@code Clock} 이 준 현재 시각. 🔴 요청이 준 값이 아니다
	 */
	public Optional<ReportFileToken> verify(String token, Instant now) {
		if (token == null) {
			return Optional.empty();
		}
		int mark = token.indexOf(SEPARATOR);
		if (mark <= 0 || mark == token.length() - 1) {
			return Optional.empty();
		}
		byte[] raw;
		byte[] presented;
		try {
			raw = decoder.decode(token.substring(0, mark));
			presented = decoder.decode(token.substring(mark + 1));
		}
		catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
		// 🔴 조기 반환 비교 금지. 길이가 달라도 isEqual 이 false 를 낸다.
		if (!MessageDigest.isEqual(sign(raw), presented)) {
			return Optional.empty();
		}
		Optional<ReportFileToken> parsed = parse(new String(raw, StandardCharsets.UTF_8));
		// 🔴 만료 검사. 이 줄이 사라지면 고의 파괴 #6 이 red 를 내야 한다.
		return parsed.filter(value -> now.isBefore(value.expiresAt()));
	}

	private Optional<ReportFileToken> parse(String payload) {
		String[] parts = payload.split(":", PAYLOAD_FIELDS);
		if (parts.length != PAYLOAD_FIELDS || !VERSION.equals(parts[0])) {
			return Optional.empty();
		}
		try {
			return Optional.of(new ReportFileToken(
				UUID.fromString(parts[1]),
				UUID.fromString(parts[2]),
				Instant.ofEpochSecond(Long.parseLong(parts[3])),
				parts[4]));
		}
		catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
	}

	private byte[] sign(byte[] payload) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));
			return mac.doFinal(payload);
		}
		catch (java.security.GeneralSecurityException error) {
			throw new IllegalStateException("HMAC-SHA256 must be available", error);
		}
	}
}
