package com.checkon.member.report.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 서명 URL 이 담고 나가는 값 전량.
 *
 * <p>🔴 {@code objectKey}·버킷·경로를 <b>담지 않는다.</b> 비우는 게 아니라 타입에 자리가 없다 —
 * 누가 나중에 「편하니까」 넣으려 하면 record 를 고쳐야 하고, 그 diff 는 리뷰에서 보인다.</p>
 *
 * <p>🔴 {@code parentProfileId} 가 들어 있는 이유 — 다운로드 시점에 관계를 <b>다시</b> 확인해야
 * 하는데, 이 경로는 세션이 없어서 주체를 알 방법이 토큰뿐이다. 🔴 쿼리 파라미터의 어떤 값도
 * DB 컨텍스트가 되지 않는다. 오직 <b>서명이 검증된</b> 이 값만 컨텍스트가 된다.</p>
 *
 * @param reportFileId    {@code member_report_files.id}
 * @param parentProfileId 발급받은 학부모. 다운로드 시점 관계 재검증의 주체가 된다
 * @param expiresAt       만료 시각. 판정은 서버가 주입된 {@code Clock} 으로 한다
 * @param nonce           같은 (파일, 학부모, 만료) 조합에서도 토큰이 갈리게 하는 난수.
 *                        🔴 순번이 아니다 — 순번이면 옆 토큰을 추측할 수 있다
 */
public record ReportFileToken(
	UUID reportFileId,
	UUID parentProfileId,
	Instant expiresAt,
	String nonce
) {

	public ReportFileToken {
		Objects.requireNonNull(reportFileId, "reportFileId");
		Objects.requireNonNull(parentProfileId, "parentProfileId");
		Objects.requireNonNull(expiresAt, "expiresAt");
		Objects.requireNonNull(nonce, "nonce");
		if (nonce.isBlank()) {
			throw new IllegalArgumentException("nonce must not be blank");
		}
	}
}
