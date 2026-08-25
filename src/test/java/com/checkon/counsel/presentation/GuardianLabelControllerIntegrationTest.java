package com.checkon.counsel.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.counsel.application.GuardianLabelDecisionService;
import com.checkon.counsel.application.GuardianLabelDecisionTransactions.StoredDecision;
import com.checkon.counsel.application.GuardianLabelSuggestionException;
import com.checkon.counsel.application.GuardianLabelSuggestionService;
import com.checkon.counsel.application.GuardianLabelSuggestionService.EligibilityReason;
import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelDecisionRepository.CurrentLabel;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest.Action;

@SpringBootTest(properties = "checkon.security.test-authentication.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("학부모 라벨 프론트엔드 API")
class GuardianLabelControllerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198f900-0000-7000-8000-000000000b01");
	private static final UUID PARENT = UUID.fromString("0198f900-0000-7000-8000-000000000b02");
	private static final UUID SUGGESTION_REF = UUID.fromString("0198f900-0000-7000-8000-000000000b03");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private GuardianLabelSuggestionService suggestions;

	@MockitoBean
	private GuardianLabelDecisionService decisions;

	@Test
	@DisplayName("Given 상담 이력이 네 건일 때 When 제안을 요청하면 Then 200과 명시적인 이력 부족 상태를 반환한다")
	void returnsAnExplicitInsufficientHistoryState() throws Exception {
		when(suggestions.suggest(TEACHER, PARENT)).thenReturn(new GuardianLabelSuggestionService.Result(
			false, EligibilityReason.INSUFFICIENT_HISTORY, false, "gd_parent", 4, List.of(), List.of()
		));

		mvc.perform(post("/api/v1/guardians/{parentId}/label-suggestions", PARENT)
				.with(teacherAuthentication()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.eligible").value(false))
			.andExpect(jsonPath("$.reason").value("INSUFFICIENT_HISTORY"))
			.andExpect(jsonPath("$.historyCount").value(4))
			.andExpect(jsonPath("$.suggestions").isEmpty());
	}

	@Test
	@DisplayName("Given 확정 라벨이 있을 때 When 현재값을 조회하면 Then 축과 값을 반환한다")
	void returnsCurrentLabels() throws Exception {
		when(decisions.currentLabels(TEACHER, PARENT)).thenReturn(List.of(
			new CurrentLabel(GuardianLabelAxis.COMM, GuardianLabelValue.DATA, Instant.parse("2026-08-25T04:00:00Z"))
		));

		mvc.perform(get("/api/v1/guardians/{parentId}/labels", PARENT)
				.with(teacherAuthentication()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.labels[0].axis").value("comm"))
			.andExpect(jsonPath("$.labels[0].value").value("data"));
	}

	@Test
	@DisplayName("Given 유효한 제안일 때 When 강사가 거절하면 Then 현재값 없이 저장된 판단을 반환한다")
	void returnsARejectedDecision() throws Exception {
		when(decisions.decide(any(), any(), any(), any(), any())).thenReturn(new StoredDecision(
			true, "tn_teacher", "gd_parent:comm:data", Action.rejected, GuardianLabelAxis.COMM, null
		));

		mvc.perform(post("/api/v1/guardians/{parentId}/label-decisions", PARENT)
				.with(teacherAuthentication())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"suggestionRef":"0198f900-0000-7000-8000-000000000b03","action":"rejected"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.suggestionRef").value(SUGGESTION_REF.toString()))
			.andExpect(jsonPath("$.action").value("rejected"))
			.andExpect(jsonPath("$.axis").value("comm"))
			.andExpect(jsonPath("$.value").doesNotExist());
	}

	@Test
	@DisplayName("Given 다른 축의 정정값일 때 When 판단하면 Then 400으로 변환한다")
	void mapsAnInvalidDecisionToBadRequest() throws Exception {
		when(decisions.decide(any(), any(), any(), any(), any()))
			.thenThrow(GuardianLabelSuggestionException.invalidDecision());

		mvc.perform(post("/api/v1/guardians/{parentId}/label-decisions", PARENT)
				.with(teacherAuthentication())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"suggestionRef":"0198f900-0000-7000-8000-000000000b03","action":"corrected","correctedValue":"anxious"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_GUARDIAN_LABEL_DECISION"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication() {
		UUID accountId = UUID.nameUUIDFromBytes(("account:" + TEACHER).getBytes(StandardCharsets.UTF_8));
		var principal = new AuthenticatedAccount(accountId, AccountRole.TEACHER, TEACHER, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}
}
