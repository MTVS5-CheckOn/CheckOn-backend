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
 * <p>🔴 <b>승우님 원장에 쓰지 않는다.</b> {@code monthly_report_deliveries.status} 를
 * {@code DELIVERED} 로 바꾸지 않는다. 멱등은 우리 원장에서 보장한다 — 아래 참조.</p>
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
	private final TeacherTenantDatabaseContext tenantContext;
	private final PublicationProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public MonthlyReportPublicationService(
		PublishedReportWriter writer,
		TeacherTenantDatabaseContext tenantContext,
		PublicationProperties properties,
		ObjectMapper objectMapper,
		Clock clock,
		PlatformTransactionManager transactionManager
	) {
		this.writer = writer;
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
	 * @return 이 배달 하나의 결과. 🔴 예외를 밖으로 던지지 않는다 — 하나가 실패해도 배치가
	 *         멈추면 안 된다. 대신 {@code failed} 로 세고 사유를 로그에 남긴다
	 */
	public PublicationOutcome publish(QueuedDelivery delivery) {
		try {
			PublicationOutcome outcome =
				transactionTemplate.execute(status -> publishInTransaction(delivery));
			return outcome == null ? new PublicationOutcome(0, 0, 0, 1, 0) : outcome;
		}
		catch (RuntimeException error) {
			// 🔴 실패를 삼키지 않는다. 무엇이 왜 실패했는지 남긴다 — 본문은 넣지 않는다.
			log.error("publication.monthly-report.failed delivery={} report={} teacher={} err={}",
				delivery.deliveryId(), delivery.reportId(), delivery.teacherId(),
				error.getClass().getSimpleName(), error);
			return new PublicationOutcome(0, 0, 0, 1, 0);
		}
	}

	private PublicationOutcome publishInTransaction(QueuedDelivery delivery) {
		tenantContext.setCurrentTeacher(delivery.teacherId());
		if (delivery.reportMonth() == null) {
			log.warn("publication.monthly-report.no-month delivery={} report={}",
				delivery.deliveryId(), delivery.reportId());
			return new PublicationOutcome(0, 0, 0, 1, 0);
		}
		String month = delivery.reportMonth().toString();
		if (writer.alreadyPublished(delivery.studentId(), delivery.teacherId(), month)) {
			// 🔴 정상 경로다. 같은 배달을 두 번 돌려도 여기서 멈춘다.
			//    다른 배달(정정본)이어도 멈춘다 — 그 판단은 MB-10 이다. 조용히 넘기지 않는다.
			log.info("publication.monthly-report.skipped-existing delivery={} report={}"
				+ " student={} month={}", delivery.deliveryId(), delivery.reportId(),
				delivery.studentId(), month);
			return new PublicationOutcome(0, 1, 0, 0, 0);
		}
		List<PublishableSection> sections = sectionsOf(delivery);
		if (sections.isEmpty()) {
			// 🔴 빈 보고서를 학부모에게 보내지 않는다. 발행하지 않고 남긴다.
			log.warn("publication.monthly-report.no-sections delivery={} report={} aiStatus={}",
				delivery.deliveryId(), delivery.reportId(), delivery.aiStatus());
			return new PublicationOutcome(0, 0, 1, 0, 0);
		}
		writer.publish(delivery.studentId(), delivery.teacherId(), month,
			properties.monthZone(), FIRST_REVISION, properties.snapshotVersion(),
			sections, clock.instant());
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
