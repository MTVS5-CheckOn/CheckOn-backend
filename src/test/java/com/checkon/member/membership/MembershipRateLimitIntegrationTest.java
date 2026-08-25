package com.checkon.member.membership;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.checkon.account.domain.AccountRole;

/**
 * 열거 방어(429)만 따로 증명한다.
 *
 * <p>🔴 별도 클래스인 이유 — {@code MemberRateLimiter} 는 컨텍스트 싱글턴이고 MockMvc 요청은
 * 전부 같은 IP 로 들어온다. 낮은 상한을 같은 클래스에 두면 <b>다른 테스트들이 서로의 카운터를
 * 소진</b>시켜 무관한 단언이 429 로 죽는다(실측으로 8건이 그렇게 깨졌다).</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4",
	"checkon.member.rate-limit.permits=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MembershipRateLimitIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String CHILD_VERIFICATION =
		"/api/v1/member/parents/me/children/verification";

	@Autowired MockMvc mockMvc;

	private UUID parentAccountId;

	@BeforeEach
	void setUp() {
		JdbcTemplate admin = adminJdbcTemplate();
		clearFixtures(admin);
		OffsetDateTime now = OffsetDateTime.now();

		UUID childAccountId = insertAccount(admin, "child@example.com", "STUDENT", now);
		insertStudent(admin, childAccountId,
			new StudentFixture("김민수", "김민수", 2, "STU-CHILD1"), now);
		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		insertParent(admin, parentAccountId, "박학부모", now);
	}

	@Test
	@DisplayName("🔴 상한까지는 200 이고 상한 + 1 회째는 429 + Retry-After 다")
	void verificationIsRateLimited() throws Exception {
		for (int attempt = 0; attempt < 3; attempt++) {
			mockMvc.perform(verification()).andExpect(status().isOk());
		}
		mockMvc.perform(verification())
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
			.andExpect(header().exists("Retry-After"));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
		verification() {
		return post(CHILD_VERIFICATION)
			.with(authentication(principalOf(parentAccountId, AccountRole.PARENT)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentPublicId\":\"STU-CHILD1\"}");
	}
}
