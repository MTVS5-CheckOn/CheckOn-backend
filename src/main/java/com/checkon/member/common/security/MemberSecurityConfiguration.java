package com.checkon.member.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.checkon.member.common.presentation.RequestIdFilter;
import com.checkon.member.integration.account.MemberJwtFilterFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * member 전용 보안 체인.
 *
 * <p>🔴 {@code @Order(0)} 이 핵심이다. 기존 체인의 {@code /api/v1/**} 가 통째로 TEACHER 전용이라,
 * member 체인이 앞서지 않으면 학생·학부모 요청이 전부 403 이 된다.</p>
 *
 * <p>🔴 {@code DevelopmentTestAuthenticationFilter} 를 넣지 않는다. dev 프로파일에서 그 필터가
 * TEACHER 를 자동 주입하므로, 넣는 순간 토큰 없는 요청이 member API 를 통과한다.</p>
 */
@Configuration
public class MemberSecurityConfiguration {

	private static final String MEMBER_PATHS = "/api/v1/member/**";
	private static final String STUDENT_PATHS = "/api/v1/member/students/**";
	private static final String PARENT_PATHS = "/api/v1/member/parents/**";
	private static final String STUDENT_SIGN_UP = "/api/v1/member/auth/students/sign-up";
	private static final String PARENT_SIGN_UP = "/api/v1/member/auth/parents/sign-up";
	private static final String STUDENT_LOGIN = "/api/v1/member/auth/students/login";
	private static final String ACTIVATION_STATUS =
		"/api/v1/member/auth/students/activation-status";
	/**
	 * 🔴 <b>세션 없는 공개 경로.</b> 브라우저의 PDF 뷰어·새 탭 열기는
	 * {@code Authorization} 헤더를 붙일 방법이 없다. 헤더를 요구하면 학부모가 PDF 를 볼 수
	 * 없으므로 인증을 URL 안으로 옮겼다 — 보안은 <b>HMAC 서명 + 짧은 TTL + 다운로드 시점
	 * 관계 재검증</b> 셋이 진다({@code ReportFileDownloadService}).
	 *
	 * <p>🔴 {@code PARENT_PATHS}({@code /member/parents/**}) 밖이라 별도 한 줄이 필요하다.
	 * 이 줄이 없으면 {@code anyRequest().authenticated()} 로 떨어져 토큰만 들고 온 요청이
	 * 401 을 받는다 — {@code ACTIVATION_STATUS} 와 같은 이유의 별도 줄이다.</p>
	 *
	 * <p>🔴 {@code permitAll} 은 <b>이 파일에만</b> 넣는다.
	 * {@code global/config/AccountSecurityConfiguration} 은 팀원 소유라 열지 않는다.</p>
	 */
	private static final String REPORT_FILE_DOWNLOAD = "/api/v1/member/files/reports/*";

	@Bean
	@Order(0)
	SecurityFilterChain memberSecurityFilterChain(
		HttpSecurity http,
		MemberJwtFilterFactory jwtFilterFactory,
		CorsConfigurationSource corsConfigurationSource,
		ObjectMapper objectMapper
	) throws Exception {
		return http
			.securityMatcher(MEMBER_PATHS)
			.csrf(csrf -> csrf.disable())
			.cors(cors -> cors.configurationSource(corsConfigurationSource))
			.sessionManagement(session ->
				session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.POST, STUDENT_SIGN_UP, PARENT_SIGN_UP, STUDENT_LOGIN)
				.permitAll()
				.requestMatchers(HttpMethod.GET, REPORT_FILE_DOWNLOAD)
				.permitAll()
				// 🔴 STUDENT_PATHS(/member/students/**) 밖이라 별도 한 줄이 필요하다.
				//    이 줄이 없으면 anyRequest().authenticated() 로 떨어져 학부모도 통과한다.
				.requestMatchers(HttpMethod.GET, ACTIVATION_STATUS)
				.hasRole(MemberRole.STUDENT.name())
				.requestMatchers(STUDENT_PATHS).hasRole(MemberRole.STUDENT.name())
				.requestMatchers(PARENT_PATHS).hasRole(MemberRole.PARENT.name())
				.anyRequest().authenticated())
			.addFilterBefore(new RequestIdFilter(), UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(jwtFilterFactory.create(), UsernamePasswordAuthenticationFilter.class)
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(new MemberAuthenticationEntryPoint(objectMapper))
				.accessDeniedHandler(new MemberAccessDeniedHandler(objectMapper)))
			.build();
	}
}
