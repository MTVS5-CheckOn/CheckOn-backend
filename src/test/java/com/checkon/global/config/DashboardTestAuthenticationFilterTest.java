package com.checkon.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.checkon.account.infrastructure.security.AuthenticatedAccount;

class DashboardTestAuthenticationFilterTest {
	private static final UUID TEACHER_ID = UUID.randomUUID();

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void injectsTeacherForEveryDashboardContractApiFamily() throws Exception {
		var filter = new DashboardTestAuthenticationFilter(
			new DashboardTestAuthenticationProperties(true, TEACHER_ID)
		);
		for (var request : new MockHttpServletRequest[] {
			request("GET", "/api/v1/dashboard/briefing"),
			request("GET", "/api/v1/engagement/alerts/id"),
			request("POST", "/api/v1/engagement/alerts/id/approval"),
			request("PATCH", "/api/v1/todos/id"),
			request("PUT", "/api/v1/students/id/personal-information/name")
		}) {
			var chain = new PrincipalCapturingFilterChain();
			filter.doFilter(request, new MockHttpServletResponse(), chain);
			assertThat(chain.principal.teacherProfileId()).isEqualTo(TEACHER_ID);
		}
	}

	@Test
	void doesNotInjectAuthenticationOutsideDashboardContract() throws Exception {
		var filter = new DashboardTestAuthenticationFilter(
			new DashboardTestAuthenticationProperties(true, TEACHER_ID)
		);
		var chain = new PrincipalCapturingFilterChain();

		filter.doFilter(
			request("POST", "/api/v1/learning-records"),
			new MockHttpServletResponse(),
			chain
		);

		assertThat(chain.principal).isNull();
	}

	@Test
	void bearerHeaderIsNeverReplacedByTestAuthentication() throws Exception {
		var filter = new DashboardTestAuthenticationFilter(
			new DashboardTestAuthenticationProperties(true, TEACHER_ID)
		);
		var request = request("GET", "/api/v1/dashboard/briefing");
		request.addHeader("Authorization", "Bearer real-token");
		var chain = new PrincipalCapturingFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(chain.principal).isNull();
	}

	@Test
	void enabledTestAuthenticationRequiresTeacherProfileId() {
		assertThatThrownBy(() -> new DashboardTestAuthenticationProperties(true, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("teacher-profile-id");
	}

	private MockHttpServletRequest request(String method, String path) {
		return new MockHttpServletRequest(method, path);
	}

	private static class PrincipalCapturingFilterChain extends MockFilterChain {
		private AuthenticatedAccount principal;

		@Override
		public void doFilter(
			jakarta.servlet.ServletRequest request,
			jakarta.servlet.ServletResponse response
		) {
			var authentication = SecurityContextHolder.getContext().getAuthentication();
			principal = authentication == null
				? null
				: (AuthenticatedAccount) authentication.getPrincipal();
		}
	}
}
