package com.checkon.member.report.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 보고서 조회·PDF 접근 설정.
 *
 * <p>🔴 상수 하드코딩 금지. 기본값은 이 한 파일에만 둔다 —
 * {@code application*.yaml} 은 팀원 소유라 무접촉이다(PR1).</p>
 *
 * <p>🔴 <b>{@code storageRoot} 와 {@code signingSecret} 에는 기본값이 없다.</b> 운영 경로나
 * 서명 키를 지어내지 않는다. 둘 중 하나라도 비면
 * {@code UnavailableObjectStorageAdapter} 가 등록되고 file-access 만 {@code 503} 이 된다 —
 * 🔴 <b>기동을 실패시키지 않는다.</b> 저장소 미설정이 목록·상세까지 죽이면 안 된다.</p>
 *
 * <p>🔴 버킷명·엔드포인트·리전·자격증명은 <b>이름만 등재하고 값을 쓰지 않는다</b>:
 * {@code MEMBER_REPORT_STORAGE_BUCKET} · {@code _ENDPOINT} · {@code _REGION} ·
 * {@code _ACCESS_KEY} · {@code _SECRET_KEY} → MB-52. 그 값들이 정해지기 전에는 로컬 파일
 * 구현 하나만 쓴다.</p>
 *
 * @param storageRoot     PDF 가 사는 디렉터리. 환경변수 {@code MEMBER_REPORT_STORAGE_ROOT}.
 *                        🔴 기본값 없음
 * @param signingSecret   서명 URL 의 HMAC 키. 환경변수
 *                        {@code MEMBER_REPORT_SIGNING_SECRET}. 🔴 기본값 없음 —
 *                        임시 키를 생성해 채우는 분기를 만들지 않는다
 * @param signedUrlTtl    서명 URL 수명. {@code PT5M} <b>PROPOSED</b> · MB-10.
 *                        🔴 유출된 URL 은 TTL 동안 유효하다 — signed URL 의 성질이지 버그가
 *                        아니다. 그래서 짧게 둔다
 * @param maxVerifyBytes  발급 전 checksum 재계산 상한. 넘으면 검증할 수 없으므로
 *                        <b>발급하지 않고</b> 503 이다. 건너뛰고 주는 분기는 없다
 * @param listMaxLimit    목록 {@code limit} 상한. 계약 {@code Limit.maximum} 과 같은 50.
 *                        🔴 초과는 조용히 깎지 않고 400 이다
 * @param listDefaultLimit 목록 기본 {@code limit}
 * @param drainMaxPerRequest 요청당 발행 알림 outbox 소비 상한 (PR7 과 같은 패턴)
 * @param notificationRetryLimit outbox 재시도 상한. 넘으면 {@code FAILED} 고정
 */
@ConfigurationProperties("checkon.member.report")
public record MemberReportProperties(
	String storageRoot,
	String signingSecret,
	Duration signedUrlTtl,
	Long maxVerifyBytes,
	Integer listMaxLimit,
	Integer listDefaultLimit,
	Integer drainMaxPerRequest,
	Integer notificationRetryLimit
) {

	public MemberReportProperties {
		signedUrlTtl = signedUrlTtl == null ? Duration.ofMinutes(5) : signedUrlTtl;
		maxVerifyBytes = maxVerifyBytes == null ? 20_971_520L : maxVerifyBytes;
		listMaxLimit = listMaxLimit == null ? 50 : listMaxLimit;
		listDefaultLimit = listDefaultLimit == null ? 20 : listDefaultLimit;
		drainMaxPerRequest = drainMaxPerRequest == null ? 5 : drainMaxPerRequest;
		notificationRetryLimit = notificationRetryLimit == null ? 5 : notificationRetryLimit;

		if (signedUrlTtl.isZero() || signedUrlTtl.isNegative()) {
			throw new IllegalArgumentException("signed-url-ttl must be positive");
		}
		if (maxVerifyBytes < 1) {
			throw new IllegalArgumentException("max-verify-bytes must be at least 1");
		}
		if (listMaxLimit < 1 || listDefaultLimit < 1 || listDefaultLimit > listMaxLimit) {
			throw new IllegalArgumentException(
				"list-default-limit must be between 1 and list-max-limit");
		}
		if (drainMaxPerRequest < 1) {
			throw new IllegalArgumentException("drain-max-per-request must be at least 1");
		}
		if (notificationRetryLimit < 1) {
			throw new IllegalArgumentException("notification-retry-limit must be at least 1");
		}
	}

	/** 저장소를 실제로 열 수 있는 설정이 다 있는가. 하나라도 없으면 file-access 만 503 이다. */
	public boolean storageConfigured() {
		return storageRoot != null && !storageRoot.isBlank()
			&& signingSecret != null && !signingSecret.isBlank();
	}
}
