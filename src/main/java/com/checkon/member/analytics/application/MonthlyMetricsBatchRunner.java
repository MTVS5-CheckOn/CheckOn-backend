package com.checkon.member.analytics.application;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배치 재계산 진입점. 명시 호출로만 돈다 — {@code @Scheduled} 등록은 이 PR 범위 밖이다
 * (운영 창·부하 미확정, {@code MEMBER_OPEN_ITEMS.md} 에 등재).
 *
 * <p>🔴 <b>{@code student_profiles} 는 RLS 밖이다</b>(전수 #10 · 0건). 배치가 학생 목록을
 * 강사 컨텍스트 없이 열 수 있는 유일한 근거다 — 없어지면 이 배치는 성립하지 않는다.</p>
 *
 * <p>🔴 <b>학생마다 별도 트랜잭션</b>에서 자기 컨텍스트를 열고 자기 PENDING 을 소비한다.
 * 한 학생이 실패해도 다음 학생으로 넘어간다.</p>
 *
 * <p>🔴 <b>강사 컨텍스트를 세팅하지 않는다</b>(G1 · 절대 규칙 3번). 🔴 {@code SECURITY DEFINER}
 * 도 {@code rolbypassrls} 역할도 쓰지 않는다.</p>
 *
 * <p>🔴 상한(<b>{@code batch-student-limit}</b>)에 걸려 남긴 학생·outbox 는 <b>{@code PENDING}
 * 그대로</b> 남는다. 다음 실행에서 처리된다. 상한을 넘겼다고 {@code DONE} 으로 지우면 계약이
 * 조용히 깨진다.</p>
 *
 * <p>이 클래스는 {@code member_metric_refresh_outbox} 를 <b>직접 읽지 않는다</b>. 학생 컨텍스트를
 * 여닫는 일은 {@link MetricRefreshOutboxDrainer} 가 하고, 이 러너는 그 진입점을
 * 학생마다 한 번 부른다 — 그래서 이 파일은 {@code G15-EXEMPT(accounts, student_profiles)}
 * 를 붙일 필요 없이 자연스럽게 통과한다.</p>
 */
@Service
public class MonthlyMetricsBatchRunner {

	private static final Logger log = LoggerFactory.getLogger(MonthlyMetricsBatchRunner.class);
	private static final String LIST_STUDENTS = """
		SELECT student.id AS student_id, student.account_id
		FROM student_profiles student
		WHERE student.account_id IS NOT NULL
		ORDER BY student.id
		LIMIT ?
		""";

	private final JdbcTemplate jdbcTemplate;
	private final MetricRefreshOutboxDrainer drainer;
	private final MemberMetricsProperties properties;
	private final TransactionTemplate transactionTemplate;

	public MonthlyMetricsBatchRunner(
		JdbcTemplate jdbcTemplate,
		MetricRefreshOutboxDrainer drainer,
		MemberMetricsProperties properties,
		PlatformTransactionManager transactionManager
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.drainer = drainer;
		this.properties = properties;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		// 🔴 학생마다 새 트랜잭션. 한 학생 실패가 다음 학생을 막지 못하게 한다.
		this.transactionTemplate.setPropagationBehavior(
			org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 학생을 순회하며 자기 outbox 를 소비한다. 반환은 처리 요약 — 상한에 걸린 잔여는 여기 없다
	 * (드레이너 로그에 나타난다).
	 */
	public BatchSummary runOnce() {
		int limit = properties.batchStudentLimit();
		List<StudentRow> students = jdbcTemplate.query(LIST_STUDENTS,
			(rs, rowNum) -> new StudentRow(
				rs.getObject("student_id", UUID.class),
				rs.getObject("account_id", UUID.class)),
			limit);
		int visited = 0;
		int failed = 0;
		for (StudentRow row : students) {
			try {
				transactionTemplate.executeWithoutResult(status ->
					drainer.drainForStudent(row.accountId(), row.studentId()));
				visited += 1;
			}
			catch (RuntimeException error) {
				log.warn("member.metrics.batch.student.failed student={} err={}",
					row.studentId(), error.getClass().getSimpleName());
				failed += 1;
			}
		}
		log.info("member.metrics.batch student={} failed={} of {} limit",
			visited, failed, limit);
		return new BatchSummary(visited, failed, students.size());
	}

	private record StudentRow(UUID studentId, UUID accountId) {
	}

	public record BatchSummary(int visited, int failed, int taken) {
	}
}
