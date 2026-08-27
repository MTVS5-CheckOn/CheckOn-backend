package com.checkon.member.report.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.common.notification.NotificationPort;
import com.checkon.member.common.notification.NotificationRequest;
import com.checkon.member.common.notification.NotificationType;
import com.checkon.member.common.persistence.MemberScopeDeniedException;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.common.security.ParentChildAccessGuard;
import com.checkon.member.report.domain.ReportPublicationOutboxRow;
import com.checkon.member.report.infrastructure.persistence
	.MemberReportPublicationOutboxRepository;

/**
 * 발행 알림 대기열 소비. {@code PENDING} 행을 읽어 해당 자녀의 <b>학부모 계정</b> 앞으로
 * PR6 의 {@link NotificationPort} 로 발행한다.
 *
 * <p>🔴 <b>어휘를 늘리지 않는다.</b> {@code type} 은 V41 의 CHECK 에 이미 있는
 * {@code REPORT_PUBLISHED} 를 그대로 쓴다(전수 #4 실측).</p>
 *
 * <p>🔴 <b>{@code title}·{@code body} 에 점수·실명을 넣지 않는다.</b> 「N월 보고서가
 * 발행되었습니다」 수준이고 내용은 앱에서 본다 — 알림은 잠금화면에도 뜬다.</p>
 *
 * <p>🔴 중복은 예외가 아니라 무시다. {@code uq_member_notifications_source} +
 * {@code ON CONFLICT DO NOTHING} 이 같은 (원본, 수신자) 두 번째 발행을 조용히 지운다.
 * 23505 를 5xx 로 올리지 않는다.</p>
 *
 * <p>🔴 학부모 컨텍스트에서 도는 이유 — 발행 주체(강사)와 수신자(학부모)가 다르고,
 * PR6 의 {@code member_notifications} INSERT 정책이
 * {@code current_checkon_account_id() IS NOT NULL} 하나뿐이라 가능하다. 그 정책을 좁히는 것은
 * PR6 의 open item 이고 여기서 기존 정책을 건드리지 않는다.</p>
 *
 * <p>🔴 상한에 걸려 남긴 건 {@code PENDING} 그대로 둔다. 상한 초과분을 {@code DONE} 으로
 * 지우면 그 알림은 영영 안 간다. 몇 건을 왜 남겼는지 로그에 적는다.</p>
 */
@Service
public class ReportPublicationNotifier {

	private static final Logger log = LoggerFactory.getLogger(ReportPublicationNotifier.class);
	private static final String SOURCE_TYPE = "MEMBER_PUBLISHED_REPORT";

	private final MemberReportPublicationOutboxRepository outbox;
	private final NotificationPort notifications;
	private final ParentChildAccessGuard accessGuard;
	private final MemberReportProperties properties;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public ReportPublicationNotifier(
		MemberReportPublicationOutboxRepository outbox,
		NotificationPort notifications,
		ParentChildAccessGuard accessGuard,
		MemberReportProperties properties,
		Clock clock,
		PlatformTransactionManager transactionManager
	) {
		this.outbox = outbox;
		this.notifications = notifications;
		this.accessGuard = accessGuard;
		this.properties = properties;
		this.clock = clock;
		// 🔴 @Transactional 을 쓰지 않는다. drainForChild 가 같은 빈의 drain 을 부르면
		//    자기 호출이라 프록시를 통과하지 못해 어노테이션이 통째로 무시된다(PR7 실측:
		//    그 자리에서 500 이 났다). 트랜잭션 경계를 코드로 명시해 그 함정을 없앤다.
		//    호출부가 read-only 트랜잭션을 열고 들어올 수 있으므로 REQUIRES_NEW 다 —
		//    합쳐지면 알림 INSERT 가 read-only 커넥션에 삼켜진다.
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 학부모 진입에서 자기 자녀의 {@code PENDING} 을 최대 상한만큼 소비한다.
	 *
	 * <p>🔴 <b>알림 발행 실패가 조회 실패가 되면 안 된다.</b> 보고서를 못 보게 만드는 것보다
	 * 알림이 늦는 편이 낫다 — 그래서 예외를 삼키고 로그만 남긴다. 삼키는 것을 여기 한 곳으로
	 * 몰아 두어야 조회 경로가 조용히 500 을 내지 않는다.</p>
	 *
	 * @return 처리한 건수. 관계가 없거나 실패하면 0
	 */
	public int drainForChild(MemberSubject subject, UUID studentId) {
		try {
			return drain(subject, studentId);
		}
		catch (MemberScopeDeniedException denied) {
			// 관계가 없다. 조회 쪽이 곧 404 를 낸다 — 여기서 먼저 던지면 사유가 흐려진다.
			return 0;
		}
		catch (RuntimeException error) {
			log.warn("member.report.notify.drain.failed student={} err={}",
				studentId, error.getClass().getSimpleName());
			return 0;
		}
	}

	/**
	 * 명시 호출 진입점. {@link ReportNotificationRunner} 도 이것을 부른다.
	 *
	 * <p>🔴 트랜잭션은 {@link TransactionTemplate} 이 연다. 어노테이션이면 위
	 * {@link #drainForChild} 의 자기 호출에서 조용히 무시된다.</p>
	 */
	public int drain(MemberSubject subject, UUID studentId) {
		int max = properties.drainMaxPerRequest();
		Integer processed = transactionTemplate.execute(status ->
			drainInTransaction(subject, studentId, max));
		return processed == null ? 0 : processed;
	}

	private int drainInTransaction(MemberSubject subject, UUID studentId, int max) {
		return accessGuard.withVerifiedChild(subject, studentId, access -> {
			List<ReportPublicationOutboxRow> pending = outbox.findPending(access.studentId(), max);
			int processed = 0;
			for (ReportPublicationOutboxRow row : pending) {
				processed += publish(subject.accountId(), row) ? 1 : 0;
			}
			if (pending.size() >= max) {
				log.info("member.report.notify.drain.trimmed student={} taken={} cap={}"
					+ " remaining stay PENDING", access.studentId(), pending.size(), max);
			}
			return processed;
		});
	}

	private boolean publish(UUID recipientAccountId, ReportPublicationOutboxRow row) {
		try {
			notifications.publish(new NotificationRequest(
				recipientAccountId,
				NotificationType.REPORT_PUBLISHED,
				title(row.reportMonth()),
				null,
				row.studentId(),
				row.reportId(),
				SOURCE_TYPE,
				row.reportId()));
			outbox.markDone(row.id(), clock.instant());
			return true;
		}
		catch (RuntimeException error) {
			// 🔴 재시도 상한을 넘으면 FAILED 고정. 무한 재시도 금지.
			log.warn("member.report.notify.failed outboxId={} report={} err={}",
				row.id(), row.reportId(), error.getClass().getSimpleName());
			outbox.markFailed(row.id(), error.getClass().getSimpleName(),
				properties.notificationRetryLimit(), clock.instant());
			return false;
		}
	}

	/**
	 * 🔴 점수·실명이 들어갈 자리가 없다. 달만 넣는다.
	 * {@code reportMonth} 는 {@code YYYY-MM} 이라 뒤 두 자리가 월이다.
	 */
	private static String title(String reportMonth) {
		String month = reportMonth.substring(reportMonth.indexOf('-') + 1);
		return Integer.parseInt(month) + "월 보고서가 발행되었습니다";
	}
}
