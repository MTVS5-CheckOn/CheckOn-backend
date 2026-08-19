package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.checkon.counsel.integration.ai.CounselClientException;
import com.checkon.counsel.integration.ai.dto.CounselDraftCreateResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;
import com.checkon.counsel.integration.ai.dto.CounselMeta;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@Testcontainers
@DisplayName("상담 초안 요청 흐름")
class CounselDraftServiceTest {

	private static final UUID TEACHER_ID = UUID.fromString("019846dc-7c00-7000-8000-000000000701");
	private static final String JOB_ID = "019846dc-7c00-7000-8000-0000000006a1";

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
		jdbcTemplate.update("DELETE FROM counsel_draft_jobs");
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_ID);
	}

	@Nested
	@DisplayName("Given 초안 생성을 요청할 때")
	class GivenCreatingADraft {

		@Test
		@DisplayName("When AI가 종단 상태로 응답하면 Then 로컬 잡 기록을 저장하고 결과를 반환한다")
		void persistsTheJobAndReturnsTheResult() {
			when(client.createDraft(any(), any())).thenReturn(succeededResponse());

			var result = service.createDraft(TEACHER_ID, sampleCommand("iq_884"));

			assertThat(result.jobId()).isEqualTo(JOB_ID);
			assertThat(result.status()).isEqualTo(CounselJobPhase.SUCCEEDED);
			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, JOB_ID).orElseThrow();
			assertThat(stored.inquiryRef()).isEqualTo("iq_884");
			assertThat(stored.jobPhase()).isEqualTo("succeeded");
			assertThat(stored.idempotencyKey()).isEqualTo("iq_884");
		}

		@Test
		@DisplayName("When 같은 Idempotency-Key로 재시도하면 Then 같은 잡 기록을 갱신한다(중복 행 생성 없음)")
		void replayingTheSameIdempotencyKeyUpsertsTheSameRow() {
			when(client.createDraft(any(), any())).thenReturn(succeededResponse());

			service.createDraft(TEACHER_ID, sampleCommand("iq_884"));
			service.createDraft(TEACHER_ID, sampleCommand("iq_884"));

			Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM counsel_draft_jobs WHERE teacher_id = ? AND idempotency_key = ?",
				Integer.class, TEACHER_ID, "iq_884"
			);
			assertThat(count).isEqualTo(1);
		}

		@Test
		@DisplayName("When 필수 필드가 비어있으면 Then AI를 호출하지 않고 거절한다")
		void rejectsAnIncompleteCommandWithoutCallingTheAiServer() {
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
		}

		@Test
		@DisplayName("When AI가 멱등 충돌을 반환하면 Then IDEMPOTENCY_CONFLICT로 변환한다")
		void mapsIdempotencyConflictFromTheAiClient() {
			when(client.createDraft(any(), any()))
				.thenThrow(CounselClientException.idempotencyConflict(null, null));

			assertThatThrownBy(() -> service.createDraft(TEACHER_ID, sampleCommand("iq_886")))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.IDEMPOTENCY_CONFLICT));
		}
	}

	@Nested
	@DisplayName("Given 초안 결과를 조회할 때")
	class GivenFetchingADraft {

		@Test
		@DisplayName("When 근거 부족으로 초안이 거부됐으면 Then 정상 결과로 그대로 전달하고 로컬 phase를 갱신한다")
		void updatesKnownPhaseAfterFetching() {
			when(client.createDraft(any(), any())).thenReturn(succeededResponse());
			service.createDraft(TEACHER_ID, sampleCommand("iq_887"));
			when(client.getDraft(eq(JOB_ID), eq("tn_demo_teacher"), any()))
				.thenReturn(rejectedInsufficientResponse());

			CounselDraftGetResponse response = service.getDraft(TEACHER_ID, "tn_demo_teacher", JOB_ID, "req-2");

			assertThat(response.data().status()).isEqualTo(CounselJobPhase.SUCCEEDED);
			assertThat(response.data().result().draftStatus()).isEqualTo(CounselDraftStatus.REJECTED_INSUFFICIENT);
			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, JOB_ID).orElseThrow();
			assertThat(stored.jobPhase()).isEqualTo("succeeded");
		}

		@Test
		@DisplayName("When AI가 404를 반환하면 Then JOB_NOT_FOUND로 변환한다")
		void mapsNotFoundFromTheAiClient() {
			when(client.getDraft(eq("missing-job"), any(), any()))
				.thenThrow(CounselClientException.notFound(null, null));

			assertThatThrownBy(() -> service.getDraft(TEACHER_ID, "tn_demo_teacher", "missing-job", null))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.JOB_NOT_FOUND));
		}
	}

	@Nested
	@DisplayName("Given 초안을 다듬을 때")
	class GivenRefiningADraft {

		@Test
		@DisplayName("When 게이트가 지시를 차단해도 Then 예외 없이 applied:false 결과를 그대로 반환한다")
		void returnsAGateBlockWithoutThrowing() {
			when(client.refineDraft(eq(JOB_ID), any(), any())).thenReturn(blockedResponse());

			CounselDraftRefineResponse response = service.refine(TEACHER_ID, new RefineCounselDraftCommand(
				"tn_demo_teacher", "req-3", "turn-uuid-1", JOB_ID, "반 평균도 넣어 주세요.", 2
			));

			assertThat(response.data().applied()).isFalse();
			assertThat(response.data().blockedReason()).isEqualTo(CounselBlockedReason.COMPARISON_EXPOSURE);
		}

		@Test
		@DisplayName("When Idempotency-Key가 없으면 Then AI를 호출하지 않고 거절한다")
		void rejectsARefineWithoutIdempotencyKey() {
			var command = new RefineCounselDraftCommand(
				"tn_demo_teacher", "req-4", "", JOB_ID, "조금 더 부드럽게 써 주세요.", 1
			);

			assertThatThrownBy(() -> service.refine(TEACHER_ID, command))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.INVALID_REQUEST));
			verifyNoInteractions(client);
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

	private static CounselDraftCreateResponse succeededResponse() {
		return new CounselDraftCreateResponse(
			new CounselDraftCreateResponse.Data(JOB_ID, CounselJobPhase.SUCCEEDED),
			null,
			new CounselMeta("019846dc-7c00-7000-8000-0000000006b2", versions())
		);
	}

	private static CounselDraftGetResponse rejectedInsufficientResponse() {
		return new CounselDraftGetResponse(
			new CounselDraftGetResponse.Data(JOB_ID, CounselJobPhase.SUCCEEDED,
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
