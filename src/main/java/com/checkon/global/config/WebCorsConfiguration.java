package com.checkon.global.config;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.checkon.account.infrastructure.security.AuthenticationProperties;

/**
 * 별도 Origin의 브라우저 프론트엔드가 CheckOn API를 호출할 수 있게 한다.
 *
 * <p>허용 Origin은 Refresh 요청의 Origin 검사와 같은 설정을 사용한다.
 * 정확한 Origin만 허용하고 credential 요청을 켜므로 와일드카드나 Origin
 * 패턴은 사용하지 않는다.</p>
 */
@Configuration(proxyBeanMethods = false)
public class WebCorsConfiguration {

	private static final Duration PREFLIGHT_CACHE_TTL = Duration.ofHours(1);
	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	@Bean
	CorsConfigurationSource corsConfigurationSource(AuthenticationProperties properties) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowCredentials(true);
		configuration.setAllowedOrigins(properties.allowedOrigins());
		configuration.setAllowedMethods(List.of(
			HttpMethod.GET.name(),
			HttpMethod.POST.name(),
			HttpMethod.PUT.name(),
			HttpMethod.PATCH.name(),
			HttpMethod.DELETE.name()
		));
		configuration.setAllowedHeaders(List.of(
			HttpHeaders.AUTHORIZATION,
			HttpHeaders.CONTENT_TYPE,
			IDEMPOTENCY_KEY_HEADER
		));
		configuration.setExposedHeaders(List.of(HttpHeaders.LOCATION));
		configuration.setMaxAge(PREFLIGHT_CACHE_TTL);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		// Swagger 같은 비업무 리소스까지 불필요하게 cross-origin 공개하지 않는다.
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}
}
