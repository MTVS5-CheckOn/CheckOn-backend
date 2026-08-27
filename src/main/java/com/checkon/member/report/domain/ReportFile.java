package com.checkon.member.report.domain;

import java.util.UUID;

/**
 * 발행된 PDF 의 메타데이터. 바이트는 object storage 에 있다.
 *
 * <p>🔴 {@code objectKey} 는 이 도메인 record 까지만 온다. 응답 DTO 에는 담을 <b>필드가 없다</b>.</p>
 *
 * @param pageCount 🔴 발행자가 준 값이 없으면 {@code null} 이다. 백엔드에 PDF 파서 의존성이
 *                  없고 {@code build.gradle} 은 무접촉이다 — 세어 보지 않은 숫자를 넣지
 *                  않는다(MB-55)
 */
public record ReportFile(
	UUID id,
	UUID reportId,
	String objectKey,
	String checksum,
	String contentType,
	long sizeBytes,
	Integer pageCount
) {
}
