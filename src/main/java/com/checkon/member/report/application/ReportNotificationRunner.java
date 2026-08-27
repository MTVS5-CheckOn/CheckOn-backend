package com.checkon.member.report.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.checkon.member.common.security.MemberSubject;

/**
 * 발행 알림 대기열의 <b>명시 호출</b> 진입점.
 *
 * <p>🔴 {@code @Scheduled} 를 붙이지 않는다. 운영 창(빈도·시간대·인스턴스 수)이 미확정이고,
 * 스케줄러를 켜는 순간 다중 인스턴스에서 같은 행을 동시에 집는다 — 중복은
 * {@code uq_member_notifications_source} 가 막지만 그건 결과이지 설계가 아니다.
 * PR7 의 {@code MonthlyMetricsBatchRunner} 와 같은 판단이다(MB-54).</p>
 *
 * <p>🔴 상시 소비 경로는 <b>학부모 목록 조회 진입</b> 하나다
 * ({@link ParentReportQueryService#list}). 이 runner 는 운영자가 명시적으로 부를 때만 돈다.</p>
 */
@Component
public class ReportNotificationRunner {

	private static final Logger log = LoggerFactory.getLogger(ReportNotificationRunner.class);

	private final ReportPublicationNotifier notifier;

	public ReportNotificationRunner(ReportPublicationNotifier notifier) {
		this.notifier = notifier;
	}

	/**
	 * 한 자녀의 대기열을 한 번 소비한다.
	 *
	 * <p>🔴 <b>학부모 주체가 필요하다.</b> {@code member_report_publication_outbox} 와
	 * {@code member_notifications} 둘 다 학부모 컨텍스트에서만 열린다 — 주체 없이 부르면
	 * 예외가 아니라 0건이다(설계 §6-4-3).</p>
	 *
	 * @return 처리한 건수
	 */
	public int runOnce(MemberSubject parent, UUID studentId) {
		int processed = notifier.drain(parent, studentId);
		log.info("member.report.notify.run student={} processed={}", studentId, processed);
		return processed;
	}
}
