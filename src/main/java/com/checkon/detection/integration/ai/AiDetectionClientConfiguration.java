package com.checkon.detection.integration.ai;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiDetectionConsentProperties.class)
public class AiDetectionClientConfiguration {

	@Bean
	RiskDetectionClient riskDetectionClient(
		@Value("${checkon.ai.base-url:http://localhost:8000}") String baseUrl,
		@Value("${checkon.ai.detect-path:/v1/detect}") String detectPath,
		@Value("${checkon.ai.connect-timeout:2s}") Duration connectTimeout,
		@Value("${checkon.ai.read-timeout:10s}") Duration readTimeout
	) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.build();
		JdkClientHttpRequestFactory requestFactory =
			new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);

		return new HttpRiskDetectionClient(
			RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.build(),
			detectPath
		);
	}
}
