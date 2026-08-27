package com.checkon.publication.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 월별 보고서 발행 배치 설정.
 *
 * <p>🔴 상수 하드코딩 금지. 기본값은 이 한 파일에만 둔다 —
 * {@code application*.yaml} 은 팀원 소유라 무접촉이다.</p>
 *
 * @param enabled          🔴 <b>기본 {@code false}</b>. 배치가 승우님 원장을 읽고 학부모에게
 *                         보이는 행을 만든다 — 운영 창이 합의되기 전에 저절로 돌면 안 된다.
 *                         켜는 것은 배포 결정이다
 * @param pollDelay        {@code @Scheduled(fixedDelayString)} 값
 * @param maxPerRun        🔴 한 회에 발행할 상한. 넘긴 건 다음 회로 남기고 <b>몇 건 남겼는지
 *                         로그에 찍는다</b>(절대 규칙 6)
 * @param snapshotVersion  발행 스냅샷 형식 버전. 형식이 바뀌면 올린다.
 *                         {@code member_published_reports.snapshot_version} 에 그대로 들어간다
 * @param monthZone        🔴 {@code monthly_reports.report_month} 는 <b>zone 없는 DATE</b> 라
 *                         원본이 어느 zone 으로 달을 잘랐는지 알 수 없다. 이 값은 「우리가 그
 *                         날짜를 어느 zone 기준으로 읽었다고 기록하는가」이고
 *                         {@code month_zone}(NOT NULL) 에 남는다. 🔴 원본의 zone 을 안다고
 *                         주장하는 값이 아니다 — MB-63
 */
@ConfigurationProperties("checkon.publication.monthly-report")
public record PublicationProperties(
	Boolean enabled,
	String pollDelay,
	Integer maxPerRun,
	String snapshotVersion,
	String monthZone
) {

	public PublicationProperties {
		enabled = enabled != null && enabled;
		pollDelay = pollDelay == null ? "5m" : pollDelay;
		maxPerRun = maxPerRun == null ? 50 : maxPerRun;
		snapshotVersion = snapshotVersion == null ? "mrp-1" : snapshotVersion;
		monthZone = monthZone == null ? "Asia/Seoul" : monthZone;

		if (maxPerRun < 1) {
			throw new IllegalArgumentException("max-per-run must be at least 1");
		}
		if (snapshotVersion.isBlank() || snapshotVersion.length() > 20) {
			throw new IllegalArgumentException("snapshot-version must be 1..20 characters");
		}
		if (monthZone.isBlank()) {
			throw new IllegalArgumentException("month-zone must not be blank");
		}
	}
}
