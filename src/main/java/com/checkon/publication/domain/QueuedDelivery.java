package com.checkon.publication.domain;

import java.time.YearMonth;
import java.util.UUID;

/**
 * 발행 대기 중인 배달 한 건. {@code monthly_report_deliveries} 와 그 보고서를 조인한 결과다.
 *
 * <p>🔴 <b>이 행의 존재가 곧 「학부모 앱으로 보낸다」는 강사의 결정이다.</b>
 * V37 이 {@code ck_monthly_report_delivery_channel CHECK (channel = 'PARENT_APP')} 로
 * 채널을 하나로 박아 뒀다 — 다른 채널이 없으므로 행이 생겼다는 것 자체가 결정이다.</p>
 *
 * <p>🔴 <b>{@code monthly_reports.request_status = 'SUCCEEDED'} 를 신호로 쓰지 않는다.</b>
 * 그건 「AI 생성이 성공했다」일 뿐 강사가 보내기로 했다는 뜻이 아니다. 그걸로 발행하면
 * 검토되지 않은 보고서가 학부모에게 나간다 — PR9/V45 가 통째로 막으려던 그 일이다.</p>
 *
 * @param reportMonth {@code monthly_reports.report_month} 는 월초로 잘린 {@code DATE} 다
 *                    (V37 {@code ck_monthly_reports_month}). 우리 컬럼은
 *                    {@code VARCHAR(7) '^\d{4}-\d{2}$'} 라 {@link YearMonth} 로 옮긴다
 * @param aiPayload   {@code monthly_reports.ai_payload} 원문(JSON 문자열). 없으면 {@code null}
 */
public record QueuedDelivery(
	UUID deliveryId,
	UUID reportId,
	UUID teacherId,
	UUID studentId,
	YearMonth reportMonth,
	String aiStatus,
	String aiPayload
) {
}
