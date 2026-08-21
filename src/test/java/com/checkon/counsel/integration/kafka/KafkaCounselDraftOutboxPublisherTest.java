package com.checkon.counsel.integration.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.checkon.counsel.infrastructure.kafka.CounselDraftOutboxRepository;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository;

/**
 * Plain unit test (no Spring context, no broker) for
 * {@link KafkaCounselDraftOutboxPublisher} — mirrors
 * {@code detection.integration.kafka.KafkaOutboxPublisher}'s publish/reschedule
 * behavior. Deliberately avoids {@code @SpringBootTest}/{@code @EmbeddedKafka}:
 * this repo's environment has repeatedly hung or OOM'd on embedded-broker
 * shutdown, and none of this class's logic needs a real Spring context.
 */
@DisplayName("상담 초안 Kafka 아웃박스 발행")
class KafkaCounselDraftOutboxPublisherTest {

	private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
	private static final String TOPIC = "checkon.counsel-draft.requested.v1";

	private CounselDraftOutboxRepository outboxRepository;
	private CounselDraftJobRepository jobRepository;
	private KafkaTemplate<String, String> kafkaTemplate;
	private KafkaCounselDraftOutboxPublisher publisher;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		outboxRepository = mock(CounselDraftOutboxRepository.class);
		jobRepository = mock(CounselDraftJobRepository.class);
		kafkaTemplate = mock(KafkaTemplate.class);
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		CounselDraftKafkaProperties properties = new CounselDraftKafkaProperties(
			true, TOPIC, "checkon.counsel-draft.completed.v1", "checkon.counsel-draft.failed.v1",
			"checkon-backend-counsel-draft-test",
			Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(10),
			20, 3
		);
		publisher = new KafkaCounselDraftOutboxPublisher(outboxRepository, jobRepository, kafkaTemplate, properties, clock);
	}

	@Nested
	@DisplayName("Given 발행 대기 중인 이벤트가 있을 때")
	class GivenAPendingEvent {

		@Test
		@DisplayName("When Kafka 전송에 성공하면 Then PUBLISHED로 표시하고 로컬 잡은 건드리지 않는다")
		void marksPublishedOnSuccess() {
			var event = claimedEvent(1);
			when(outboxRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(event));
			when(kafkaTemplate.send(eq(TOPIC), eq(event.messageKey()), eq(event.payload())))
				.thenReturn(CompletableFuture.completedFuture(null));

			int published = publisher.publishDue();

			assertThat(published).isEqualTo(1);
			verify(outboxRepository).markPublished(eq(event.id()), any());
			verifyNoInteractions(jobRepository);
		}

		@Test
		@DisplayName("When Kafka 전송이 실패하고 재시도 여력이 있으면 Then 재스케줄만 하고 로컬 잡은 건드리지 않는다")
		void reschedulesOnARecoverableFailure() {
			var event = claimedEvent(1);
			when(outboxRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(event));
			when(kafkaTemplate.send(any(), any(), any())).thenThrow(new RuntimeException("kafka down"));

			int published = publisher.publishDue();

			assertThat(published).isZero();
			verify(outboxRepository).reschedule(eq(event.id()), eq(1), eq(3), any(Instant.class), any(String.class));
			verifyNoInteractions(jobRepository);
		}

		@Test
		@DisplayName("When 재시도 횟수를 모두 소진하면 Then 로컬 잡을 failed로 표시해 폴링이 영원히 queued에 멈추지 않게 한다")
		void failsTheLocalJobWhenAttemptsAreExhausted() {
			var event = claimedEvent(3);
			when(outboxRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(event));
			when(kafkaTemplate.send(any(), any(), any())).thenThrow(new RuntimeException("kafka down"));

			publisher.publishDue();

			verify(outboxRepository).reschedule(eq(event.id()), eq(3), eq(3), any(Instant.class), any(String.class));
			verify(jobRepository).updateKnownPhase(eq(event.teacherId()), eq(event.jobId().toString()), eq("failed"), any());
		}
	}

	private static CounselDraftOutboxRepository.ClaimedEvent claimedEvent(int publishAttempts) {
		return new CounselDraftOutboxRepository.ClaimedEvent(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TOPIC, "tn_demo_teacher", "{}", publishAttempts
		);
	}
}
