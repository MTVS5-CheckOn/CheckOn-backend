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

class DevelopmentTestAuthenticationFilterTest {
	private static final UUID TEACHER_ID = UUID.randomUUID();
	private static final UUID ACCOUNT_ID = UUID.randomUUID();

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void injectsTeacherForAnyProtectedApiHandledByThisSecurityChain() throws Exception {
		var filter = new DevelopmentTestAuthenticationFilter(
			new DevelopmentTestAuthenticationProperties(true, ACCOUNT_ID, TEACHER_ID)
		);
		for (var request : new MockHttpServletRequest[] {
			request("GET", "/api/v1/dashboard/briefing"),
			request("POST", "/api/v1/learning-records"),
			request("POST", "/api/v1/detection-runs"),
			request("PATCH", "/api/v1/todos/id")
		}) {
			var chain = new PrincipalCapturingFilterChain();
			filter.doFilter(request, new MockHttpServletResponse(), chain);
			assertThat(chain.principal.teacherProfileId()).isEqualTo(TEACHER_ID);
			assertThat(chain.principal.accountId()).isEqualTo(ACCOUNT_ID);
		}
	}

	@Test
	void bearerHeaderIsNeverReplacedByTestAuthentication() throws Exception {
		var filter = new DevelopmentTestAuthenticationFilter(
			new DevelopmentTestAuthenticationProperties(true, ACCOUNT_ID, TEACHER_ID)
		);
		var request = request("GET", "/api/v1/dashboard/briefing");
		request.addHeader("Authorization", "Bearer real-token");
		var chain = new PrincipalCapturingFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(chain.principal).isNull();
	}

	@Test
	void disabledTestAuthenticationDoesNotInjectPrincipal() throws Exception {
		var filter = new DevelopmentTestAuthenticationFilter(
			new DevelopmentTestAuthenticationProperties(false, null, null)
		);
		var chain = new PrincipalCapturingFilterChain();

		filter.doFilter(
			request("GET", "/api/v1/dashboard/briefing"),
			new MockHttpServletResponse(),
			chain
		);

		assertThat(chain.principal).isNull();
	}

	@Test
	void enabledTestAuthenticationRequiresRealAccountAndTeacherProfileIds() {
		assertThatThrownBy(() -> new DevelopmentTestAuthenticationProperties(
			true, null, TEACHER_ID
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("account-id");
		assertThatThrownBy(() -> new DevelopmentTestAuthenticationProperties(
			true, ACCOUNT_ID, null
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("teacher-profile-id");
	}

	@Test
	void authenticationLifecycleEndpointsAreNotFaked() throws Exception {
		var filter = new DevelopmentTestAuthenticationFilter(
			new DevelopmentTestAuthenticationProperties(true, ACCOUNT_ID, TEACHER_ID)
		);
		var chain = new PrincipalCapturingFilterChain();

		filter.doFilter(
			request("POST", "/api/v1/auth/logout"),
			new MockHttpServletResponse(),
			chain
		);

		assertThat(chain.principal).isNull();
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
