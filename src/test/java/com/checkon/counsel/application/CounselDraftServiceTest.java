package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.counsel.domain.CounselBlockedReason;
import com.checkon.counsel.domain.CounselDraftStatus;
import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository;
import com.checkon.counsel.integration.ai.CounselClient;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;
import com.checkon.counsel.integration.ai.dto.CounselMeta;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@Testcontainers
@DisplayName("상담 초안 요청 흐름")
class CounselDraftServiceTest {

	private static final UUID TEACHER_ID = UUID.fromString("019846dc-7c00-7000-8000-000000000701");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@MockitoBean
	private CounselClient client;

	@Autowired
	private CounselDraftService service;

	@Autowired
	private CounselDraftJobRepository jobRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM counsel_draft_kafka_outbox_events");
		jdbcTemplate.update("DELETE FROM counsel_draft_jobs");
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_ID);
	}

	@Nested
	@DisplayName("Given 초안 생성을 요청할 때")
	class GivenCreatingADraft {

		@Test
		@DisplayName("When 요청하면 Then job_id를 즉시 minting해 queued로 저장하고 카프카 아웃박스에 발행한다")
		void mintsTheJobIdAndPublishesToTheOutbox() {
			var result = service.createDraft(TEACHER_ID, sampleCommand("iq_884"));

			assertThat(result.status()).isEqualTo(CounselJobPhase.QUEUED);
			assertThat(result.meta()).isNull();
			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, result.jobId()).orElseThrow();
			assertThat(stored.inquiryRef()).isEqualTo("iq_884");
			assertThat(stored.jobPhase()).isEqualTo("queued");
			assertThat(stored.idempotencyKey()).isEqualTo("iq_884");
			assertThat(stored.aiJobId()).isNull();
			Integer outboxCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM counsel_draft_kafka_outbox_events WHERE counsel_draft_job_id = ?",
				Integer.class, stored.id()
			);
			assertThat(outboxCount).isEqualTo(1);
			verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When 같은 Idempotency-Key로 같은 본문을 재시도하면 Then 같은 job_id를 반환하고 다시 발행하지 않는다")
		void replayingTheSameIdempotencyKeyReturnsTheSameJobWithoutRepublishing() {
			var first = service.createDraft(TEACHER_ID, sampleCommand("iq_884"));

			var second = service.createDraft(TEACHER_ID, sampleCommand("iq_884"));

			assertThat(second.jobId()).isEqualTo(first.jobId());
			Integer jobCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM counsel_draft_jobs WHERE teacher_id = ? AND idempotency_key = ?",
				Integer.class, TEACHER_ID, "iq_884"
			);
			assertThat(jobCount).isEqualTo(1);
			Integer outboxCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM counsel_draft_kafka_outbox_events WHERE teacher_id = ?",
				Integer.class, TEACHER_ID
			);
			assertThat(outboxCount).isEqualTo(1);
		}

		@Test
		@DisplayName("When 같은 Idempotency-Key에 다른 본문이 오면 Then IDEMPOTENCY_CONFLICT로 거절한다")
		void rejectsAReplayWithADifferentBodyUnderTheSameKey() {
			service.createDraft(TEACHER_ID, sampleCommand("iq_886"));
			var differentTopic = withTopic(sampleCommand("iq_886"), CounselTopic.ETC);

			assertThatThrownBy(() -> service.createDraft(TEACHER_ID, differentTopic))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.IDEMPOTENCY_CONFLICT));
		}

		@Test
		@DisplayName("When 필수 필드가 비어있으면 Then 아웃박스에 발행하지 않고 거절한다")
		void rejectsAnIncompleteCommandWithoutPublishing() {
			var incomplete = new CreateCounselDraftCommand(
				"tn_demo_teacher", "req-1", "iq_885", "iq_885",
				CounselTopic.GRADE, CounselUrgency.NORMAL, OffsetDateTime.now(),
				"", "st_1", "pa_1", "cl_1", List.of(), List.of(), "sha256:abc", "2026년 7월", List.of()
			);

			assertThatThrownBy(() -> service.createDraft(TEACHER_ID, incomplete))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.INVALID_REQUEST));
			verifyNoInteractions(client);
			Integer outboxCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM counsel_draft_kafka_outbox_events WHERE teacher_id = ?", Integer.class, TEACHER_ID);
			assertThat(outboxCount).isZero();
		}
	}

	@Nested
	@DisplayName("Given 초안 결과를 조회할 때")
	class GivenFetchingADraft {

		@Test
		@DisplayName("When ai_job_id를 아직 모르면 Then AI를 호출하지 않고 로컬 phase만 반환한다")
		void returnsTheLocalPhaseWithoutCallingTheAiServerWhenAiJobIdIsUnknown() {
			var created = service.createDraft(TEACHER_ID, sampleCommand("iq_887"));

			CounselDraftGetResponse response = service.getDraft(TEACHER_ID, "tn_demo_teacher", created.jobId(), "req-2");

			assertThat(response.data().status()).isEqualTo(CounselJobPhase.QUEUED);
			assertThat(response.data().result()).isNull();
			assertThat(response.meta()).isNull();
			verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When ai_job_id를 알고 있으면 Then AI를 호출해 결과를 반환하고 로컬 phase를 갱신한다")
		void callsTheAiServerAndUpdatesKnownPhaseWhenAiJobIdIsKnown() {
			var created = service.createDraft(TEACHER_ID, sampleCommand("iq_887b"));
			jobRepository.setAiOutcome(TEACHER_ID, created.jobId(), "cj_ai_887", CounselJobPhase.RUNNING.wireValue(), null, Instant.now());
			when(client.getDraft(eq("cj_ai_887"), eq("tn_demo_teacher"), any()))
				.thenReturn(rejectedInsufficientResponse("cj_ai_887"));

			CounselDraftGetResponse response = service.getDraft(TEACHER_ID, "tn_demo_teacher", created.jobId(), "req-2");

			assertThat(response.data().status()).isEqualTo(CounselJobPhase.SUCCEEDED);
			assertThat(response.data().result().draftStatus()).isEqualTo(CounselDraftStatus.REJECTED_INSUFFICIENT);
			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, created.jobId()).orElseThrow();
			assertThat(stored.jobPhase()).isEqualTo("succeeded");
		}

		@Test
		@DisplayName("When 로컬에 없는 job_id를 조회하면 Then AI를 호출하지 않고 JOB_NOT_FOUND로 거절한다")
		void rejectsAnUnknownLocalJobIdWithoutCallingTheAiServer() {
			assertThatThrownBy(() -> service.getDraft(TEACHER_ID, "tn_demo_teacher", "missing-job", null))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.JOB_NOT_FOUND));
			verifyNoInteractions(client);
		}
	}

	@Nested
	@DisplayName("Given 초안을 다듬을 때")
	class GivenRefiningADraft {

		@Test
		@DisplayName("When 게이트가 지시를 차단해도 Then 예외 없이 applied:false 결과를 그대로 반환한다")
		void returnsAGateBlockWithoutThrowing() {
			var created = service.createDraft(TEACHER_ID, sampleCommand("iq_888"));
			jobRepository.setAiOutcome(TEACHER_ID, created.jobId(), "cj_ai_888", CounselJobPhase.RUNNING.wireValue(), null, Instant.now());
			when(client.refineDraft(eq("cj_ai_888"), any(), any())).thenReturn(blockedResponse());

			CounselDraftRefineResponse response = service.refine(TEACHER_ID, new RefineCounselDraftCommand(
				"tn_demo_teacher", "req-3", "turn-uuid-1", created.jobId(), "반 평균도 넣어 주세요.", 2
			));

			assertThat(response.data().applied()).isFalse();
			assertThat(response.data().blockedReason()).isEqualTo(CounselBlockedReason.COMPARISON_EXPOSURE);
		}

		@Test
		@DisplayName("When ai_job_id를 아직 모르면 Then AI를 호출하지 않고 DRAFT_NOT_READY로 거절한다")
		void rejectsRefiningBeforeAiJobIdIsKnown() {
			var created = service.createDraft(TEACHER_ID, sampleCommand("iq_889"));

			assertThatThrownBy(() -> service.refine(TEACHER_ID, new RefineCounselDraftCommand(
				"tn_demo_teacher", "req-4", "turn-uuid-2", created.jobId(), "조금 더 부드럽게 써 주세요.", 1
			)))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.DRAFT_NOT_READY));
			verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When Idempotency-Key가 없으면 Then AI를 호출하지 않고 거절한다")
		void rejectsARefineWithoutIdempotencyKey() {
			var command = new RefineCounselDraftCommand(
				"tn_demo_teacher", "req-5", "", "019846dc-7c00-7000-8000-0000000006a1", "조금 더 부드럽게 써 주세요.", 1
			);

			assertThatThrownBy(() -> service.refine(TEACHER_ID, command))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.INVALID_REQUEST));
			verifyNoInteractions(client);
		}
	}

	@Nested
	@DisplayName("Given 강사가 발송을 완료했을 때")
	class GivenMarkingADraftAsSent {

		@Test
		@DisplayName("When 발송본을 기록하면 Then AI를 호출하지 않고 로컬 잡에 발송 본문을 남긴다")
		void recordsTheSentTextLocallyWithoutCallingTheAiServer() {
			var created = service.createDraft(TEACHER_ID, sampleCommand("iq_890"));

			service.markSent(TEACHER_ID, created.jobId(), "어머님, 발송한 실제 문구입니다.");

			verifyNoInteractions(client);
			Integer sentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM counsel_draft_jobs WHERE job_id = ? AND sent_text IS NOT NULL",
				Integer.class, created.jobId()
			);
			assertThat(sentCount).isEqualTo(1);
		}

		@Test
		@DisplayName("When 존재하지 않는 job을 기록하려 하면 Then JOB_NOT_FOUND로 거절한다")
		void rejectsMarkingAnUnknownJobAsSent() {
			assertThatThrownBy(() -> service.markSent(TEACHER_ID, "missing-job", "발송 본문"))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.JOB_NOT_FOUND));
		}
	}

	private CreateCounselDraftCommand sampleCommand(String idempotencyKey) {
		return new CreateCounselDraftCommand(
			"tn_demo_teacher", "req-" + idempotencyKey, idempotencyKey, idempotencyKey,
			CounselTopic.GRADE, CounselUrgency.IMMEDIATE, OffsetDateTime.parse("2026-07-31T14:20:00+09:00"),
			"요즘 아이가 힘들어하는 것 같은데 학원에서는 뭘 하고 있는 건가요?",
			"st_8f2a", "pa_9c1d", "cl_a1",
			List.of("narrative", "anxious"),
			List.of(new CreateCounselDraftCommand.DismissedSuggestion("frequency", "monthly")),
			"sha256:7d1e0000000000000000000000000000000000000000000000000000000000",
			"2026년 7월",
			List.of(
				new CreateCounselDraftCommand.Fact("le_2041", "6월 지문 42개·312문항"),
				new CreateCounselDraftCommand.Fact(null, "최근 4주 정답률 평균 81%")
			)
		);
	}

	private static CreateCounselDraftCommand withTopic(CreateCounselDraftCommand command, CounselTopic topic) {
		return new CreateCounselDraftCommand(
			command.tenantAlias(), command.requestId(), command.idempotencyKey(), command.inquiryRef(),
			topic, command.urgency(), command.receivedAt(), command.textMasked(),
			command.studentRef(), command.parentRef(), command.classRef(), command.labels(),
			command.dismissedSuggestions(), command.snapshotHash(), command.periodLabel(), command.facts()
		);
	}

	private static CounselDraftGetResponse rejectedInsufficientResponse(String aiJobId) {
		return new CounselDraftGetResponse(
			new CounselDraftGetResponse.Data(aiJobId, CounselJobPhase.SUCCEEDED,
				new CounselDraftGetResponse.Result(
					CounselDraftStatus.REJECTED_INSUFFICIENT, null, List.of(), List.of(), List.of(),
					"no_citable_evidence", OffsetDateTime.now()
				)),
			null,
			new CounselMeta("019846dc-7c00-7000-8000-0000000006b2", versions())
		);
	}

	private static CounselDraftRefineResponse blockedResponse() {
		return new CounselDraftRefineResponse(
			new CounselDraftRefineResponse.Data(false, null, List.of(), CounselBlockedReason.COMPARISON_EXPOSURE),
			null,
			new CounselMeta("019846dc-7c00-7000-8000-0000000006b2", versions())
		);
	}

	private static CounselMeta.Versions versions() {
		return new CounselMeta.Versions("0.1.0", "counsel-pack-0.1", null, "0.3", "0.1", "0.1", null, null, null, null);
	}
}
