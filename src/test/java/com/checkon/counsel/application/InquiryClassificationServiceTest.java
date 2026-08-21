package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import com.checkon.counsel.domain.ClassifyFallbackReason;
import com.checkon.counsel.domain.ConfirmationAction;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.domain.InquirySentiment;
import com.checkon.counsel.infrastructure.persistence.InquiryClassificationRepository;
import com.checkon.counsel.integration.ai.ClassifyClient;
import com.checkon.counsel.integration.ai.ClassifyClientException;
import com.checkon.counsel.integration.ai.dto.ClassifyResponse;
import com.checkon.counsel.integration.ai.dto.ConfirmationResponse;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@Testcontainers
@DisplayName("문의 분류 흐름")
class InquiryClassificationServiceTest {

	private static final UUID TEACHER_ID = UUID.fromString("019846dc-7c00-7000-8000-000000000901");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@MockitoBean
	private ClassifyClient client;

	@Autowired
	private InquiryClassificationService service;

	@Autowired
	private InquiryClassificationRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM counsel_inquiry_classifications");
		RosterTestFixture.insertTeacher(jdbcTemplate, TEACHER_ID);
	}

	@Nested
	@DisplayName("Given 문의를 분류할 때")
	class GivenClassifyingAnInquiry {

		@Test
		@DisplayName("When AI가 분류에 성공하면 Then 예측값을 로컬에 저장한다")
		void persistsThePrediction() {
			when(client.classify(any(), any(), any())).thenReturn(classifiedResponse("iq_301", CounselTopic.SCHEDULE));

			var result = service.classify(TEACHER_ID, "iq_301", "여름방학 특강 시간표가 궁금합니다");

			assertThat(result.topic()).isEqualTo(CounselTopic.SCHEDULE);
			var stored = repository.findByTeacherAndInquiryRef(TEACHER_ID, "iq_301").orElseThrow();
			assertThat(stored.predictedTopic()).isEqualTo("schedule");
			assertThat(stored.effectiveTopic()).isEqualTo("schedule");
			assertThat(stored.confirmationAction()).isNull();
		}

		@Test
		@DisplayName("When 필수 입력이 비어있으면 Then AI를 호출하지 않고 거절한다")
		void rejectsBlankInputWithoutCallingTheAiServer() {
			assertThatThrownBy(() -> service.classify(TEACHER_ID, "iq_302", ""))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.INVALID_REQUEST));
			verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When AI가 500을 반환하면 Then 재시도해도 소용없는 예외로 변환한다")
		void mapsInternalErrorDistinctlyFromRetryableFaults() {
			when(client.classify(any(), any(), any()))
				.thenThrow(ClassifyClientException.internalError(null, null));

			assertThatThrownBy(() -> service.classify(TEACHER_ID, "iq_303", "문의합니다"))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.UPSTREAM_INTERNAL_ERROR));
		}
	}

	@Nested
	@DisplayName("Given 강사가 분류를 확인·정정할 때")
	class GivenConfirmingAClassification {

		@Test
		@DisplayName("When topic만 부분 정정하면 Then 그 축만 갱신되고 나머지 예측값은 유지된다")
		void updatesOnlyTheCorrectedAxis() {
			when(client.classify(any(), any(), any())).thenReturn(classifiedResponse("iq_304", CounselTopic.ETC));
			service.classify(TEACHER_ID, "iq_304", "문의합니다");
			when(client.confirm(any(), any(), any())).thenReturn(acceptedResponse());

			service.confirm(TEACHER_ID, "iq_304", ConfirmationAction.CORRECTED, CounselTopic.COUNSEL_REQUEST, null, null);

			var stored = repository.findByTeacherAndInquiryRef(TEACHER_ID, "iq_304").orElseThrow();
			assertThat(stored.effectiveTopic()).isEqualTo("counsel_request");
			assertThat(stored.effectiveSentiment()).isEqualTo(stored.predictedSentiment());
			assertThat(stored.confirmationAction()).isEqualTo("corrected");
		}

		@Test
		@DisplayName("When action=corrected인데 정정값이 하나도 없으면 Then AI를 호출하지 않고 거절한다")
		void rejectsACorrectionWithoutAnyCorrectedValue() {
			assertThatThrownBy(() -> service.confirm(TEACHER_ID, "iq_305", ConfirmationAction.CORRECTED, null, null, null))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.INVALID_REQUEST));
			verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When action=confirmed인데 정정값이 실려 있으면 Then AI를 호출하지 않고 거절한다")
		void rejectsAConfirmationCarryingACorrectedValue() {
			assertThatThrownBy(() -> service.confirm(
				TEACHER_ID, "iq_306", ConfirmationAction.CONFIRMED, CounselTopic.GRADE, null, null
			))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.INVALID_REQUEST));
			verifyNoInteractions(client);
		}

		@Test
		@DisplayName("When 저장된 분류가 없으면 Then AI의 404를 CLASSIFICATION_NOT_FOUND로 변환한다")
		void mapsNotFoundFromTheAiClient() {
			when(client.confirm(any(), any(), any()))
				.thenThrow(ClassifyClientException.notFound(null, null));

			assertThatThrownBy(() -> service.confirm(
				TEACHER_ID, "iq_missing", ConfirmationAction.CONFIRMED, null, null, null
			))
				.isInstanceOf(CounselException.class)
				.satisfies(exception -> assertThat(((CounselException) exception).reason())
					.isEqualTo(CounselException.Reason.CLASSIFICATION_NOT_FOUND));
		}

		@Test
		@DisplayName("When 정정이 topic이 아니면 Then 초안을 다시 만들지 않는다")
		void doesNotRedraftForANonTopicCorrection() {
			when(client.classify(any(), any(), any())).thenReturn(classifiedResponse("iq_307", CounselTopic.GRADE));
			service.classify(TEACHER_ID, "iq_307", "문의합니다");
			when(client.confirm(any(), any(), any())).thenReturn(acceptedResponse());

			var redraft = service.confirm(
				TEACHER_ID, "iq_307", ConfirmationAction.CORRECTED, null, InquirySentiment.COMPLAINT, null
			);

			assertThat(redraft).isEmpty();
		}
	}

	private static ClassifyResponse classifiedResponse(String inquiryRef, CounselTopic topic) {
		return new ClassifyResponse(
			new ClassifyResponse.Data(
				inquiryRef, topic, InquirySentiment.NORMAL, CounselUrgency.NORMAL,
				new ClassifyResponse.Confidence(BigDecimal.valueOf(0.9), BigDecimal.valueOf(0.9), BigDecimal.valueOf(0.9)),
				true, (ClassifyFallbackReason) null
			),
			null, null
		);
	}

	private static ConfirmationResponse acceptedResponse() {
		return new ConfirmationResponse(new ConfirmationResponse.Data(true), null, null);
	}
}
