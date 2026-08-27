package com.checkon.publication.application;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.publication.domain.AiReportPayload;
import com.checkon.publication.domain.PublicationOutcome;
import com.checkon.publication.domain.PublishableSection;
import com.checkon.publication.domain.QueuedDelivery;
import com.checkon.publication.infrastructure.PublishedReportWriter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 배달 <b>한 건</b>을 발행한다. 🔴 <b>한 배달 = 한 트랜잭션</b>이라 하나가 실패해도 나머지는
 * 계속 간다.
 *
 * <p>🔴 <b>이 경계는 강사 컨텍스트만 연다.</b> 학부모·학생 컨텍스트를 같은 트랜잭션에서 절대
 * 열지 않는다 — V45 정책이 {@code PERMISSIVE} 라 여러 정책이 <b>OR 로 합쳐진다.</b> 둘 다
 * 열리면 「강사 자기 것」과 「학부모 자기 자녀 것」이 동시에 참이 되어 격리가 무너진다.
 * 그것이 불변식의 진짜 내용이고, {@code PublicationContextRuleTest} 가 게이트로 막는다.</p>
 *
 * <p>🔴 <b>승우님 원장에 쓰는 것은 딱 하나</b> — 발행에 성공한 배달의
 * {@code status='DELIVERED'} 표시다(MB-61). 주인이 없음을 실측으로 확인하고 넣었다:
 * 승우님 코드는 {@code QUEUED} 만 만들고, {@code DELIVERED} 를 쓰는 코드가 밖에 0곳이며,
 * 유일한 소비처가 QUEUED 와 DELIVERED 를 똑같이 취급한다({@code QueuedDeliveryReader} 참조).
 * 🔴 <b>실패는 표시하지 않는다</b> — {@code QUEUED} 로 남아야 다음 회차에 재시도된다.</p>
 *
 * <p>🔴 <b>멱등 판정은 그대로 둔다.</b> 표시가 실패하거나 경합으로 밀려도 두 벌이 안 생기는
 * 것이 마지막 방어선이다 — 표시는 <b>비용을 줄이는 장치</b>이지 정합성의 근거가 아니다.</p>
 *
 * <p>🔴 <b>민감 문자열을 로그에 넣지 않는다.</b> 보고서 본문·학생 이름·AI 원문은 어느 로그에도
 * 나가지 않는다. 식별자와 사유 코드만 남긴다(PR8 이 그 검사를 만들어 뒀다).</p>
 */
@Service
public class MonthlyReportPublicationService {

	private static final Logger log =
		LoggerFactory.getLogger(MonthlyReportPublicationService.class);

	/**
	 * 🔴 <b>첫 발행은 항상 revision 1 이다.</b> 정정 발행(revision 2 이상)은 「학부모에게
	 * 이전 revision 을 계속 보일 것인가」가 정해져야 하는데 그게 MB-10 으로 미확정이다.
	 * 그래서 이 배치는 <b>같은 (학생·강사·달) 두 번째 배달을 건너뛰고 로그로 알린다</b> —
	 * 조용히 덮어쓰거나 조용히 두 벌 만들지 않는다.
	 */
	private static final int FIRST_REVISION = 1;

	private final PublishedReportWriter writer;
	private final com.checkon.publication.infrastructure.QueuedDeliveryReader reader;
	private final TeacherTenantDatabaseContext tenantContext;
	private final PublicationProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public MonthlyReportPublicationService(
		PublishedReportWriter writer,
		com.checkon.publication.infrastructure.QueuedDeliveryReader reader,
		TeacherTenantDatabaseContext tenantContext,
		PublicationProperties properties,
		ObjectMapper objectMapper,
		Clock clock,
		PlatformTransactionManager transactionManager
	) {
		this.writer = writer;
		this.reader = reader;
		this.tenantContext = tenantContext;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
		// 🔴 @Transactional 을 쓰지 않는다. 러너가 같은 빈의 메서드를 부르면 자기 호출이라
		//    프록시를 통과하지 못해 어노테이션이 통째로 무시된다(PR7 실측: 그 자리에서 500).
		//    배달마다 독립 트랜잭션이어야 하므로 REQUIRES_NEW 다.
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 이 강사의 <b>다음</b> 대기 배달 한 건을 잠그고 발행한다.
	 *
	 * <p>🔴 <b>집는 것과 쓰는 것이 한 트랜잭션</b>이다. 잠금은 트랜잭션이 끝나면 풀리므로
	 * 나눠 두면 {@code SKIP LOCKED} 가 아무것도 지키지 못한다.</p>
	 *
	 * @return 처리 결과. 🔴 {@link PublicationOutcome#handled()} 가 0이면 <b>더 없다</b>는 뜻이고
	 *         호출자가 그 강사에 대한 반복을 멈춘다
	 */
	public Attempt publishNext(java.util.UUID teacherId, java.util.Collection<java.util.UUID> excluded) {
		try {
			Attempt attempt =
				transactionTemplate.execute(status -> publishNextInTransaction(teacherId, excluded));
			return attempt == null ? Attempt.none() : attempt;
		}
		catch (RuntimeException error) {
			// 🔴 실패를 삼키지 않는다. 무엇이 왜 실패했는지 남긴다 — 본문은 넣지 않는다.
			//    🔴 롤백이므로 배달은 QUEUED 그대로다 — 다음 회차에 재시도된다.
			log.error("publication.monthly-report.failed teacher={} err={}",
				teacherId, error.getClass().getSimpleName(), error);
			// 🔴 어느 배달이었는지 모르므로 제외할 수 없다. 호출자가 이 강사를 건너뛴다 —
			//    그렇게 하지 않으면 같은 행에서 무한히 실패한다.
			return new Attempt(new PublicationOutcome(0, 0, 0, 1, 0), null, false);
		}
	}

	private Attempt publishNextInTransaction(
		java.util.UUID teacherId, java.util.Collection<java.util.UUID> excluded
	) {
		tenantContext.setCurrentTeacher(teacherId);
		QueuedDelivery delivery = reader.lockNextQueued(excluded).orElse(null);
		if (delivery == null) {
			return Attempt.none();
		}
		PublicationOutcome outcome = publishLocked(delivery);
		// 🔴 표시되지 않은 배달(발행할 내용 없음)은 QUEUED 로 남는다 — 이번 회차에서
		//    다시 집히지 않도록 제외 대상으로 돌려준다.
		boolean marked = outcome.published() > 0 || outcome.skipped() > 0;
		return new Attempt(outcome, delivery.deliveryId(), marked);
	}

	/**
	 * 한 번의 시도 결과.
	 *
	 * @param deliveryId 집은 배달. 아무것도 못 집었거나 예외였으면 {@code null}
	 * @param marked     🔴 {@code DELIVERED} 로 표시했는가. {@code false} 면 호출자가
	 *                   이번 회차 제외 목록에 넣어야 한다
	 */
	public record Attempt(PublicationOutcome outcome, java.util.UUID deliveryId, boolean marked) {

		public static Attempt none() {
			return new Attempt(PublicationOutcome.none(), null, false);
		}

		/** 집을 것이 더 있었는가. */
		public boolean touched() {
			return outcome.handled() > 0;
		}
	}

	private PublicationOutcome publishLocked(QueuedDelivery delivery) {
		if (delivery.reportMonth() == null) {
			log.warn("publication.monthly-report.no-month delivery={} report={}",
				delivery.deliveryId(), delivery.reportId());
			return new PublicationOutcome(0, 0, 0, 1, 0);
		}
		String month = delivery.reportMonth().toString();
		if (writer.alreadyPublished(delivery.studentId(), delivery.teacherId(), month)) {
			// 🔴 정상 경로다. 표시가 어떤 이유로 밀렸어도 여기서 두 벌을 막는다 —
			//    이것이 마지막 방어선이고 DELIVERED 표시는 비용을 줄이는 장치일 뿐이다.
			//    다른 배달(정정본)이어도 멈춘다 — 그 판단은 MB-10 이다. 조용히 넘기지 않는다.
			log.info("publication.monthly-report.skipped-existing delivery={} report={}"
				+ " student={} month={}", delivery.deliveryId(), delivery.reportId(),
				delivery.studentId(), month);
			// 🔴 이미 우리 원장에 있으니 이 배달은 할 일이 끝났다. 표시해서 다음 회차에
			//    다시 집지 않게 한다 — 안 하면 skip 카운터가 영원히 자란다.
			reader.markDelivered(delivery.deliveryId(), clock.instant());
			return new PublicationOutcome(0, 1, 0, 0, 0);
		}
		List<PublishableSection> sections = sectionsOf(delivery);
		if (sections.isEmpty()) {
			// 🔴 빈 보고서를 학부모에게 보내지 않는다. 발행하지 않고 남긴다.
			log.warn("publication.monthly-report.no-sections delivery={} report={} aiStatus={}",
				delivery.deliveryId(), delivery.reportId(), delivery.aiStatus());
			return new PublicationOutcome(0, 0, 1, 0, 0);
		}
		java.time.Instant publishedAt = clock.instant();
		writer.publish(delivery.studentId(), delivery.teacherId(), month,
			properties.monthZone(), FIRST_REVISION, properties.snapshotVersion(),
			sections, publishedAt);
		// 🔴 성공했을 때만 표시한다. 같은 트랜잭션이라 발행과 표시가 함께 커밋되거나 함께 없다.
		reader.markDelivered(delivery.deliveryId(), publishedAt);
		log.info("publication.monthly-report.published delivery={} report={} student={}"
			+ " month={} sections={}", delivery.deliveryId(), delivery.reportId(),
			delivery.studentId(), month, sections.size());
		return new PublicationOutcome(1, 0, 0, 0, 0);
	}

	/** 🔴 못 읽는 payload 는 빈 목록이다. 지어내지 않고 「발행할 내용 없음」으로 흐른다. */
	private List<PublishableSection> sectionsOf(QueuedDelivery delivery) {
		if (delivery.aiPayload() == null || delivery.aiPayload().isBlank()) {
			return List.of();
		}
		try {
			return AiReportPayload.toSections(objectMapper.readTree(delivery.aiPayload()));
		}
		catch (JacksonException malformed) {
			log.warn("publication.monthly-report.unreadable-payload delivery={} report={}",
				delivery.deliveryId(), delivery.reportId());
			return List.of();
		}
	}
}
