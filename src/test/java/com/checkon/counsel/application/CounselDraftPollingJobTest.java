package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

import com.checkon.counsel.domain.CounselJobPhase;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository.NewJob;
import com.checkon.counsel.integration.ai.CounselClient;
import com.checkon.counsel.integration.ai.CounselClientException;
import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselMeta;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@Testcontainers
@DisplayName("상담 초안 잡 폴링")
class CounselDraftPollingJobTest {

	private static final UUID TEACHER_A = UUID.fromString("019846dc-7c00-7000-8000-000000000b01");
	private static final UUID TEACHER_B = UUID.fromString("019846dc-7c00-7000-8000-000000000b02");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@MockitoBean
	private CounselClient client;

	@Autowired
	private CounselDraftPollingJob job;

	@Autowired
	private CounselDraftJobRepository jobRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM counsel_draft_jobs");
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_A);
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_B);
	}

	@Nested
	@DisplayName("Given 비종단 잡이 여러 강사에 걸쳐 있을 때")
	class GivenNonTerminalJobsAcrossTeachers {

		@Test
		@DisplayName("When 폴링하면 Then 각 강사 테넌트로 GET을 호출해 로컬 phase를 갱신한다")
		void refreshesEachTeachersPendingJobs() {
			insertJob(TEACHER_A, "iq_a1", "cj_a1", "queued");
			insertJob(TEACHER_B, "iq_b1", "cj_b1", "running");
			when(client.getDraft(eq("cj_a1"), any(), any())).thenReturn(getResponse("cj_a1", CounselJobPhase.SUCCEEDED));
			when(client.getDraft(eq("cj_b1"), any(), any())).thenReturn(getResponse("cj_b1", CounselJobPhase.FAILED));

			var summary = job.pollAll();

			assertThat(summary.jobsRefreshed()).isEqualTo(2);
			assertThat(summary.failed()).isZero();
			assertThat(jobRepository.findByTeacherAndJobId(TEACHER_A, "cj_a1").orElseThrow().jobPhase()).isEqualTo("succeeded");
			assertThat(jobRepository.findByTeacherAndJobId(TEACHER_B, "cj_b1").orElseThrow().jobPhase()).isEqualTo("failed");
		}

		@Test
		@DisplayName("When 이미 종단인 잡만 있으면 Then GET을 부르지 않는다")
		void skipsAlreadyTerminalJobs() {
			insertJob(TEACHER_A, "iq_a2", "cj_a2", "succeeded");

			var summary = job.pollAll();

			assertThat(summary.jobsRefreshed()).isZero();
			org.mockito.Mockito.verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When 한 강사의 GET이 실패해도 Then 다른 강사는 계속 폴링된다")
		void isolatesFailuresPerTeacher() {
			insertJob(TEACHER_A, "iq_a3", "cj_a3", "queued");
			insertJob(TEACHER_B, "iq_b3", "cj_b3", "queued");
			when(client.getDraft(eq("cj_a3"), any(), any())).thenThrow(CounselClientException.networkError(null));
			when(client.getDraft(eq("cj_b3"), any(), any())).thenReturn(getResponse("cj_b3", CounselJobPhase.SUCCEEDED));

			var summary = job.pollAll();

			assertThat(summary.jobsRefreshed()).isEqualTo(1);
			assertThat(jobRepository.findByTeacherAndJobId(TEACHER_A, "cj_a3").orElseThrow().jobPhase()).isEqualTo("queued");
			assertThat(jobRepository.findByTeacherAndJobId(TEACHER_B, "cj_b3").orElseThrow().jobPhase()).isEqualTo("succeeded");
		}
	}

	private void insertJob(UUID teacherId, String inquiryRef, String jobId, String jobPhase) {
		Instant now = Instant.now();
		jobRepository.insertIfAbsent(new NewJob(
			UUID.randomUUID(), teacherId, "tn_demo_teacher", inquiryRef,
			"st_" + inquiryRef, "pa_" + inquiryRef, "cl_" + inquiryRef, "grade",
			inquiryRef, jobId, jobId, jobPhase, null, "sha256:" + inquiryRef, now, now
		));
	}

	private static CounselDraftGetResponse getResponse(String jobId, CounselJobPhase phase) {
		return new CounselDraftGetResponse(
			new CounselDraftGetResponse.Data(jobId, phase, null),
			null,
			new CounselMeta("019846dc-7c00-7000-8000-0000000006b2", versions())
		);
	}

	private static CounselMeta.Versions versions() {
		return new CounselMeta.Versions("0.1.0", "counsel-pack-0.1", null, "0.3", "0.1", "0.1", null, null, null, null);
	}
}
