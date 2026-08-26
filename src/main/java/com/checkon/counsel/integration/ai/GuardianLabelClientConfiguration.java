package com.checkon.counsel.integration.ai;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GuardianLabelClientConfiguration {

	@Bean
	GuardianLabelClient guardianLabelClient(
		@Value("${checkon.ai.labels.adapter-base-url:http://localhost:8081}") String baseUrl,
		@Value("${checkon.ai.labels.suggest-path:/v1/labels/suggest}") String suggestPath,
		@Value("${checkon.ai.labels.confirmations-path:/v1/confirmations}") String confirmationsPath,
		@Value("${checkon.ai.labels.connect-timeout:2s}") Duration connectTimeout,
		@Value("${checkon.ai.labels.read-timeout:30s}") Duration readTimeout
	) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return new HttpGuardianLabelClient(
			RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(), suggestPath, confirmationsPath
		);
	}
}
