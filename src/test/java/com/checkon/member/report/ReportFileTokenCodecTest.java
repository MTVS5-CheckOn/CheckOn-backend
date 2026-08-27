package com.checkon.member.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.checkon.member.report.domain.ReportFileToken;
import com.checkon.member.report.domain.ReportFileTokenCodec;

/**
 * 공개 다운로드 토큰의 성질을 스프링 없이 직접 잰다.
 *
 * <p>🔴 통합 테스트는 「404 가 나온다」까지만 본다 — <b>왜</b> 404 인지(서명이 틀려서인지
 * 만료라서인지)는 계약상 구분해서 노출하지 않으므로 밖에서 볼 수 없다. 그 구분을 여기서 잰다.</p>
 */
class ReportFileTokenCodecTest {

	private static final String SECRET = "test-signing-secret-not-a-real-credential";
	private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

	private final ReportFileTokenCodec codec = new ReportFileTokenCodec(SECRET);

	@Test
	@DisplayName("발급한 토큰은 만료 전에는 같은 값으로 되돌아온다")
	void roundTripsBeforeExpiry() {
		UUID fileId = UUID.randomUUID();
		UUID parentId = UUID.randomUUID();
		Instant expiresAt = NOW.plusSeconds(300);

		String token = codec.issue(fileId, parentId, expiresAt);
		ReportFileToken parsed = codec.verify(token, NOW).orElseThrow();

		assertThat(parsed.reportFileId()).isEqualTo(fileId);
		assertThat(parsed.parentProfileId()).isEqualTo(parentId);
		assertThat(parsed.expiresAt()).isEqualTo(expiresAt);
	}

	@Test
	@DisplayName("🔴 만료 시각 정각부터 거절한다 — 경계에서 한 칸 새지 않는다")
	void rejectsAtAndAfterExpiry() {
		Instant expiresAt = NOW.plusSeconds(300);
		String token = codec.issue(UUID.randomUUID(), UUID.randomUUID(), expiresAt);

		assertThat(codec.verify(token, expiresAt.minusSeconds(1))).isPresent();
		assertThat(codec.verify(token, expiresAt)).isEmpty();
		assertThat(codec.verify(token, expiresAt.plusSeconds(1))).isEmpty();
	}

	@Test
	@DisplayName("🔴 payload 를 한 글자라도 바꾸면 서명이 깨진다")
	void rejectsTamperedPayload() {
		UUID fileId = UUID.randomUUID();
		UUID parentId = UUID.randomUUID();
		String token = codec.issue(fileId, parentId, NOW.plusSeconds(300));
		int mark = token.indexOf('.');
		String payload = token.substring(0, mark);
		String signature = token.substring(mark + 1);

		// 🔴 「남의 파일을 가리키게 payload 만 바꾼」 시나리오. 서명은 그대로 붙인다.
		String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
			.encodeToString(("v1:" + UUID.randomUUID() + ":" + parentId + ":"
				+ NOW.plusSeconds(300).getEpochSecond() + ":deadbeef")
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		assertThat(forgedPayload).isNotEqualTo(payload);
		assertThat(codec.verify(forgedPayload + "." + signature, NOW)).isEmpty();
	}

	@Test
	@DisplayName("🔴 다른 키로 만든 토큰은 통과하지 못한다")
	void rejectsForeignSignature() {
		ReportFileTokenCodec other = new ReportFileTokenCodec(SECRET + "x");
		String token = other.issue(UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(300));

		assertThat(codec.verify(token, NOW)).isEmpty();
	}

	@Test
	@DisplayName("형식이 깨진 입력은 예외가 아니라 빈 결과다")
	void rejectsMalformedInput() {
		assertThat(codec.verify(null, NOW)).isEmpty();
		assertThat(codec.verify("", NOW)).isEmpty();
		assertThat(codec.verify("nodot", NOW)).isEmpty();
		assertThat(codec.verify(".", NOW)).isEmpty();
		assertThat(codec.verify("!!!.!!!", NOW)).isEmpty();
		assertThat(codec.verify("abc.", NOW)).isEmpty();
	}

	@Test
	@DisplayName("🔴 같은 인자로 두 번 발급해도 토큰이 다르다 — 순번이 아니라 난수다")
	void issuesUnguessableTokens() {
		UUID fileId = UUID.randomUUID();
		UUID parentId = UUID.randomUUID();
		Instant expiresAt = NOW.plusSeconds(300);

		String first = codec.issue(fileId, parentId, expiresAt);
		String second = codec.issue(fileId, parentId, expiresAt);

		assertThat(first).isNotEqualTo(second);
		// 🔴 nonce 가 짧으면 열거가 가능하다. 16바이트 = 32 hex 자리를 실제로 확인한다.
		assertThat(codec.verify(first, NOW).orElseThrow().nonce()).hasSize(32);
	}

	@Test
	@DisplayName("🔴 토큰 어디에도 object key 가 들어 있지 않다")
	void tokenNeverCarriesObjectKey() {
		String token = codec.issue(UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(300));
		int mark = token.indexOf('.');
		String payload = new String(
			java.util.Base64.getUrlDecoder().decode(token.substring(0, mark)),
			java.nio.charset.StandardCharsets.UTF_8);

		assertThat(payload).doesNotContain("reports/", ".pdf", "bucket", "/");
	}
}
