package com.checkon.member.membership;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.IdempotentOutcome;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.common.security.MemberRole;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.membership.application.ChildRegistrationCommand;
import com.checkon.member.membership.application.ChildRegistrationService;

/**
 * 자녀 등록의 경쟁 상태. 🔴 <b>MockMvc 가 아니라 application 서비스를 두 스레드에서 직접 부른다</b> —
 * MockMvc 는 동시 호출용이 아니다.
 *
 * <p>🔴 <b>테스트 메서드에 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 두 스레드가 한
 * 트랜잭션을 공유해 경쟁이 사라지고 테스트가 <b>항상 green</b> 이 된다 — 아무것도 증명하지 못한다.</p>
 *
 * <p>🔴 어느 스레드가 이기는지는 단언하지 않는다. 단언 대상은 <b>결과 집합</b>과
 * <b>최종 행 수</b>다.</p>
 *
 * <p>🔴 이 스위트는 컨트롤러를 우회해 서비스를 직접 부르므로 리미터를 타지 않는다. 상한 조정이
 * 필요 없지만, 공유 컨텍스트라 앞 클래스가 남긴 오버라이드가 살아 있을 수 있어 {@code @BeforeEach}
 * 에서 초기 상태로 되돌린다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChildRegistrationConcurrencyIntegrationTest extends MembershipRlsEnforcedSupport {

	@Autowired ChildRegistrationService childRegistrationService;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private UUID childProfileId;
	private MemberSubject firstParent;
	private MemberSubject secondParent;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		clearFixtures(admin);
		OffsetDateTime now = OffsetDateTime.now();

		UUID childAccountId = insertAccount(admin, "child@example.com", "STUDENT", now);
		childProfileId = insertStudent(admin, childAccountId,
			new StudentFixture("김민수", "김민수", 2, "STU-CHILD1"), now);

		firstParent = parentSubject(admin, "parent1@example.com", "박학부모", now);
		secondParent = parentSubject(admin, "parent2@example.com", "최학부모", now);

		// 🔴 공유 컨텍스트라 앞 클래스가 남긴 오버라이드가 살아 있을 수 있다 — 기본값으로 되돌린다.
		rateLimiter.resetForTesting();
	}

	@RepeatedTest(value = 5, name = "동시 2요청 {currentRepetition}/{totalRepetitions}")
	@DisplayName("🔴 두 학부모가 같은 순간에 등록하면 정확히 하나만 성공한다")
	void concurrentRegistrationYieldsExactlyOneSuccess() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<String> first = executor.submit(
				() -> attempt(ready, start, firstParent, UUID.randomUUID().toString()));
			Future<String> second = executor.submit(
				() -> attempt(ready, start, secondParent, UUID.randomUUID().toString()));

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<String> outcomes = List.of(
				first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
			assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "CHILD_ALREADY_LINKED");
		}
		finally {
			executor.shutdownNow();
		}

		// 🔴 최종 보장은 uq_parent_student_relationships_active_student 다.
		assertThat(countActiveLinks()).isEqualTo(1);
		assertThat(activationStatus()).isEqualTo(MemberActivationStatus.ACTIVE.name());
	}

	@Test
	@DisplayName("🔴 같은 key·같은 본문의 동시 2요청은 행을 하나만 만든다 — 하나는 재생이다")
	void concurrentSameKeyCreatesOneRow() throws Exception {
		String sharedKey = UUID.randomUUID().toString();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<String> first = executor.submit(
				() -> attempt(ready, start, firstParent, sharedKey));
			Future<String> second = executor.submit(
				() -> attempt(ready, start, firstParent, sharedKey));

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<String> outcomes = List.of(
				first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
			// 하나는 본 처리, 하나는 저장된 응답 재생이다. 둘 다 201 이다(§0-4).
			assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "REPLAYED");
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(countActiveLinks()).isEqualTo(1);
		Integer records = admin.queryForObject(
			"SELECT count(*) FROM member_idempotency_records", Integer.class);
		assertThat(records).as("멱등 기록도 하나뿐이어야 한다").isEqualTo(1);
	}

	/**
	 * 🔴 결과를 성공/{@code MemberErrorCode} 로 환원한다.
	 * {@code catch (Exception e) { return false; }} 로 뭉개지 않는다 — 어떤 오류였는지가
	 * 이 테스트의 전부다. 알 수 없는 예외는 그대로 올려 테스트를 깨뜨린다.
	 */
	private String attempt(
		CountDownLatch ready, CountDownLatch start, MemberSubject subject, String key
	) throws InterruptedException {
		ready.countDown();
		start.await();
		ChildRegistrationCommand command = new ChildRegistrationCommand(
			"STU-CHILD1", key, "{\"studentPublicId\":\"STU-CHILD1\"}");
		try {
			IdempotentOutcome outcome = childRegistrationService.register(subject, command);
			return outcome.replayed() ? "REPLAYED" : "CREATED";
		}
		catch (MemberException exception) {
			return exception.errorCode().name();
		}
	}

	private MemberSubject parentSubject(
		JdbcTemplate jdbc, String email, String displayName, OffsetDateTime now
	) {
		UUID accountId = insertAccount(jdbc, email, "PARENT", now);
		UUID profileId = insertParent(jdbc, accountId, displayName, now);
		return new MemberSubject(accountId, MemberRole.PARENT, UUID.randomUUID(),
			null, profileId, null);
	}

	private int countActiveLinks() {
		Integer count = admin.queryForObject("SELECT count(*) FROM parent_student_relationships"
			+ " WHERE student_id = ? AND status = 'ACTIVE'", Integer.class, childProfileId);
		return count == null ? 0 : count;
	}

	private String activationStatus() {
		return admin.queryForObject(
			"SELECT status FROM member_student_activation WHERE student_id = ?",
			String.class, childProfileId);
	}
}
