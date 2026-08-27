package com.checkon.member.report.application.dto;

import java.time.Instant;

/**
 * 계약 {@code ReportFileAccess}(member-api.yaml:2290).
 *
 * <p>🔴 <b>{@code objectKey}·{@code bucket}·{@code path} 를 담는 필드가 존재하지 않는다.</b>
 * 값을 비우는 게 아니라 타입에 자리가 없다 — 누가 담으려 하면 이 record 를 고쳐야 하고
 * 그 diff 는 리뷰에서 보인다(V44 의 {@code published_at NOT NULL} 과 같은 방식:
 * 실수를 컴파일 오류로 만든다).</p>
 *
 * @param url       🔴 수명이 짧은 signed URL. {@code /api/v1/member/files/reports/{token}} 이다
 * @param pageCount 🔴 발행자가 준 값이 없으면 {@code null}. 0 으로 채우지 않는다
 */
public record ReportFileAccessResponse(
	String url,
	Instant expiresAt,
	String contentType,
	String checksum,
	long sizeBytes,
	Integer pageCount
) {
}
