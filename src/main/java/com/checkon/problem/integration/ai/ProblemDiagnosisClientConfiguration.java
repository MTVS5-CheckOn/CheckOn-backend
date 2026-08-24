package com.checkon.problem.integration.ai;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ProblemDiagnosisClientConfiguration {
	@Bean
	ProblemDiagnosisClient problemDiagnosisClient(
		@Value("${checkon.ai.problem-generation.adapter-base-url:http://localhost:8081}") String baseUrl,
		@Value("${checkon.ai.problem-generation.diagnosis-path:/internal/v1/problem-diagnoses}") String path,
		@Value("${checkon.ai.problem-generation.connect-timeout:2s}") Duration connectTimeout,
		@Value("${checkon.ai.problem-generation.diagnosis-read-timeout:10s}") Duration readTimeout
	) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return new HttpProblemDiagnosisClient(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(), path);
	}
}
