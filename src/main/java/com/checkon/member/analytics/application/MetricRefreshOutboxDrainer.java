package com.checkon.member.analytics.application;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.analytics.domain.MetricRefreshOutboxRow;
import com.checkon.member.analytics.infrastructure.persistence.MemberMetricRefreshOutboxRepository;
import com.checkon.member.common.persistence.MemberDatabaseContext;

/**
 * 학생 요청 진입 시 자기 PENDING outbox 를 최대 상한만큼 소비한다(설계 §11-1 인라인 drain 패턴).
 *
 * <p>🔴 <b>제출 트랜잭션에서 집계를 부르지 않는다</b> — 제출이 분석 계산에 인질로 잡히지 않도록,
 * 제출은 outbox INSERT 만 하고 실제 재계산은 이 drainer 가 학생 진입 시점에 한다.</p>
 *
 * <p>🔴 상한(<b>{@code drain-max-per-request}</b>)을 넘긴 PENDING 은 그대로 남는다 —
 * 다음 진입에서 이어서 처리된다. 상한 초과분을 {@code DONE} 으로 지우면 학생이 다음 달을
 * 여는 순간 옛 셀이 그대로 남는다.</p>
 *
 * <p>재시도 상한(기본 5)을 넘긴 실패는 {@code FAILED} 로 고정 — 무한 재시도 금지.</p>
 */
@Service
public class MetricRefreshOutboxDrainer {

	private static final Logger log = LoggerFactory.getLogger(MetricRefreshOutboxDrainer.class);
	// 🔴 이 서비스는 outbox Repository 를 쓴다. G15 규칙 — application 서비스가 RLS 테이블을
	//    직접 쓸 때는 자기 트랜잭션에서 컨텍스트를 연다. setCurrentStudent 를 트랜잭션 안에서 부른다.
	private static final int RETRY_LIMIT = 5;

	private final MemberMetricRefreshOutboxRepository outbox;
	private final MonthlyMetricsAggregator aggregator;
	private final MemberDatabaseContext databaseContext;
	private final MemberMetricsProperties properties;
	private final Clock clock;

	public MetricRefreshOutboxDrainer(
		MemberMetricRefreshOutboxRepository outbox,
		MonthlyMetricsAggregator aggregator,
		MemberDatabaseContext databaseContext,
		MemberMetricsProperties properties,
		Clock clock
	) {
		this.outbox = outbox;
		this.aggregator = aggregator;
		this.databaseContext = databaseContext;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 학생 진입에서 자기 PENDING 을 최대 상한만큼 소비한다. 트랜잭션은 <b>이 메서드</b>가 연다 —
	 * 상위 조회 서비스가 열지 않는다(각 outbox 행이 독립적으로 성공/실패해야 한다).
	 */
	@Transactional
	public DrainResult drainForStudent(UUID accountId, UUID studentId) {
		databaseContext.setCurrentAccount(accountId);
		databaseContext.setCurrentStudent(studentId);
		int max = properties.drainMaxPerRequest();
		List<MetricRefreshOutboxRow> pending = outbox.findPending(studentId, max);
		int processed = 0;
		int failed = 0;
		for (MetricRefreshOutboxRow row : pending) {
			try {
				YearMonth month = YearMonth.parse(row.month());
				// 🔴 재계산은 별도 트랜잭션이 아니라 <b>이 트랜잭션 안</b>에서 돈다 —
				//    aggregator 도 학생 self 만 다루므로 컨텍스트가 같다.
				aggregator.recompute(accountId, studentId, month);
				outbox.markDone(row.id(), clock.instant());
				processed += 1;
			}
			catch (RuntimeException error) {
				log.warn("member.metrics.drain.failed outboxId={} student={} month={} err={}",
					row.id(), studentId, row.month(), error.getClass().getSimpleName());
				outbox.markFailed(row.id(), error.getClass().getSimpleName(),
					RETRY_LIMIT, clock.instant());
				failed += 1;
			}
		}
		if (pending.size() >= max) {
			log.info("member.metrics.drain.trimmed student={} taken={} left>=? true",
				studentId, pending.size());
		}
		return new DrainResult(processed, failed, pending.size());
	}

	/** 소비 결과. 잔여 판단은 {@code taken == max} 로 상위 서비스가 한다. */
	public record DrainResult(int processed, int failed, int taken) {
	}
}
