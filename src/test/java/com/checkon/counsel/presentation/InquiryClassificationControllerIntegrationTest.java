package com.checkon.counsel.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.domain.InquirySentiment;
import com.checkon.counsel.integration.ai.ClassifyClient;
import com.checkon.counsel.integration.ai.ClassifyClientException;
import com.checkon.counsel.integration.ai.dto.ClassifyResponse;
import com.checkon.counsel.integration.ai.dto.ConfirmationResponse;
import com.checkon.support.RosterTestFixture;

@SpringBootTest(properties = "checkon.security.test-authentication.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("문의 분류 프론트엔드 API")
class InquiryClassificationControllerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198f900-0000-7000-8000-000000000901");

	@MockitoBean
	private ClassifyClient client;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM counsel_inquiry_classifications");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
	}

	@Nested
	@DisplayName("Given 문의를 분류할 때")
	class GivenClassifyingAnInquiry {

		@Test
		@DisplayName("When AI가 분류에 성공하면 Then 분류 결과를 반환한다")
		void returnsTheClassificationResult() throws Exception {
			when(client.classify(any(), any(), any())).thenReturn(new ClassifyResponse(
				new ClassifyResponse.Data(
					"iq_401", CounselTopic.SCHEDULE, InquirySentiment.NORMAL, CounselUrgency.NORMAL,
					new ClassifyResponse.Confidence(BigDecimal.valueOf(0.95), BigDecimal.valueOf(0.88), BigDecimal.valueOf(0.91)),
					true, null
				),
				null, null
			));

			mvc.perform(post("/api/v1/counsel/inquiries/{inquiryRef}/classify", "iq_401")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "text": "여름방학 특강 시간표가 궁금합니다" }
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.topic").value("schedule"))
				.andExpect(jsonPath("$.classified").value(true));

			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM counsel_inquiry_classifications WHERE teacher_id = ? AND inquiry_ref = ?",
				Integer.class, TEACHER, "iq_401"
			)).isEqualTo(1);
		}

		@Test
		@DisplayName("When 문의 본문이 비어있으면 Then 400을 반환한다")
		void rejectsBlankText() throws Exception {
			mvc.perform(post("/api/v1/counsel/inquiries/{inquiryRef}/classify", "iq_402")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "text": "" }
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	@Nested
	@DisplayName("Given 강사가 분류를 확인·정정할 때")
	class GivenConfirmingAClassification {

		@Test
		@DisplayName("When 단순 확인이면 Then 204를 반환한다")
		void confirmsWithoutCorrection() throws Exception {
			when(client.confirm(any(), any(), any()))
				.thenReturn(new ConfirmationResponse(new ConfirmationResponse.Data(true), null, null));

			mvc.perform(post("/api/v1/counsel/inquiries/{inquiryRef}/confirmation", "iq_403")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "action": "confirmed" }
						"""))
				.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("When 저장된 분류가 없으면 Then 404를 반환한다")
		void mapsClassificationNotFound() throws Exception {
			when(client.confirm(any(), any(), any()))
				.thenThrow(ClassifyClientException.notFound(null, null));

			mvc.perform(post("/api/v1/counsel/inquiries/{inquiryRef}/confirmation", "iq_missing")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{ "action": "confirmed" }
						"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COUNSEL_CLASSIFICATION_NOT_FOUND"));
		}

		@Test
		@DisplayName("When action이 없으면 Then 400을 반환한다")
		void rejectsAMissingAction() throws Exception {
			mvc.perform(post("/api/v1/counsel/inquiries/{inquiryRef}/confirmation", "iq_404")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(UUID teacherId) {
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + teacherId).getBytes(StandardCharsets.UTF_8));
		var principal = new AuthenticatedAccount(accountId, AccountRole.TEACHER, teacherId, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}
}
