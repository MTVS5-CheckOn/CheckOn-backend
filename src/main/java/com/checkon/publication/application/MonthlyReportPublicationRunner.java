package com.checkon.publication.application;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.publication.domain.PublicationOutcome;
import com.checkon.publication.domain.QueuedDelivery;
import com.checkon.publication.infrastructure.QueuedDeliveryReader;

/**
 * 발행 배치의 진입점. 강사마다 대기 배달을 찾아 {@link MonthlyReportPublicationService} 에 넘긴다.
 *
 * <p>🔴 <b>승우님 코드에 훅을 넣지 않는다.</b> 훅을 넣으려면 그분 파일을 고쳐야 하고,
 * 이 작업의 「승우님 파일 수정 0건」이 깨진다. 그래서 배치가 밖에서 폴링한다.</p>
 *
 * <p>🔴 <b>왜 강사를 훑는가</b> — {@code monthly_report_deliveries} 는 RLS 가 켜져 있고
 * 정책이 {@code teacher_id = current_checkon_teacher_id()} 다. 컨텍스트 없이는 0행이라
 * <b>「어느 강사에게 대기 배달이 있는가」를 먼저 물어볼 방법이 없다.</b> 강사 목록만 RLS
 * 밖이라({@code teacher_profiles}) 거기서 출발한다.</p>
 *
 * <p>🔴 <b>기본값은 꺼짐이다.</b> 이 배치가 학부모에게 보이는 행을 만든다 — 운영 창이
 * 합의되기 전에 저절로 돌면 안 된다. 켜는 것은 배포 결정이다(MB-64).</p>
 *
 * <p>🔴 <b>상한에 걸려 남긴 건수를 로그에 찍는다</b>(절대 규칙 6). 「돌았는데 조용하다」와
 * 「상한에 걸려 절반이 남았다」가 구분돼야 한다.</p>
 */
@Component
public class MonthlyReportPublicationRunner {

	private static final Logger log =
		LoggerFactory.getLogger(MonthlyReportPublicationRunner.class);

	private final QueuedDeliveryReader reader;
	private final MonthlyReportPublicationService publicationService;
	private final TeacherTenantDatabaseContext tenantContext;
	private final PublicationProperties properties;
	private final TransactionTemplate readOnlyTransactionTemplate;

	public MonthlyReportPublicationRunner(
		QueuedDeliveryReader reader,
		MonthlyReportPublicationService publicationService,
		TeacherTenantDatabaseContext tenantContext,
		PublicationProperties properties,
		PlatformTransactionManager transactionManager
	) {
		this.reader = reader;
		this.publicationService = publicationService;
		this.tenantContext = tenantContext;
		this.properties = properties;
		// 🔴 대기 배달 조회만 하는 트랜잭션이다. 발행은 배달마다 REQUIRES_NEW 로 따로 연다 —
		//    조회 트랜잭션에 합치면 하나가 실패할 때 나머지까지 롤백된다.
		this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
		this.readOnlyTransactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readOnlyTransactionTemplate.setReadOnly(true);
	}

	/**
	 * 🔴 {@code enabled} 가 기본 {@code false} 라 등록만 되고 아무것도 하지 않는다.
	 * 스케줄 자체를 조건부로 만들지 않은 이유는, 켜고 끄는 것을 <b>한 값</b>으로 두어야
	 * 「왜 안 도는가」를 한 곳에서 답할 수 있기 때문이다.
	 */
	@Scheduled(fixedDelayString =
		"${checkon.publication.monthly-report.poll-delay:5m}")
	public void runScheduled() {
		if (!properties.enabled()) {
			return;
		}
		runOnce();
	}

	/**
	 * 한 회 실행. 🔴 <b>명시 호출 진입점이기도 하다</b> — 테스트와 운영자가 같은 경로를 쓴다.
	 *
	 * @return 이번 회 집계. 🔴 {@code remaining} 은 상한에 걸려 남긴 수다
	 */
	public PublicationOutcome runOnce() {
		int budget = properties.maxPerRun();
		PublicationOutcome total = PublicationOutcome.none();
		int remaining = 0;
		for (UUID teacherId : reader.findAllTeacherIds()) {
			if (budget <= 0) {
				remaining += countQueued(teacherId);
				continue;
			}
			List<QueuedDelivery> queued = queuedFor(teacherId, budget + 1);
			int taken = Math.min(queued.size(), budget);
			for (int index = 0; index < taken; index++) {
				total = total.plus(publicationService.publish(queued.get(index)));
			}
			remaining += queued.size() - taken;
			budget -= taken;
		}
		PublicationOutcome result = total.withRemaining(remaining);
		if (result.handled() > 0 || result.remaining() > 0) {
			log.info("publication.monthly-report.run published={} skipped={} empty={}"
				+ " failed={} remaining={} cap={}", result.published(), result.skipped(),
				result.empty(), result.failed(), result.remaining(), properties.maxPerRun());
		}
		if (result.remaining() > 0) {
			// 🔴 잘랐으면 무엇을 왜 잘랐는지 말한다. 다음 회에 이어서 처리한다.
			log.warn("publication.monthly-report.capped remaining={} cap={}"
				+ " — 남은 건은 다음 회차에서 처리한다", result.remaining(), properties.maxPerRun());
		}
		return result;
	}

	/** 🔴 강사 컨텍스트를 연 읽기 트랜잭션. 밖에서 읽으면 예외가 아니라 0행이다. */
	private List<QueuedDelivery> queuedFor(UUID teacherId, int limit) {
		List<QueuedDelivery> found = readOnlyTransactionTemplate.execute(status -> {
			tenantContext.setCurrentTeacher(teacherId);
			return reader.findQueued(limit);
		});
		return found == null ? List.of() : found;
	}

	/** 상한을 다 쓴 뒤 남은 수를 세기만 한다. 🔴 세는 것도 강사 컨텍스트가 필요하다. */
	private int countQueued(UUID teacherId) {
		return queuedFor(teacherId, properties.maxPerRun()).size();
	}
}
