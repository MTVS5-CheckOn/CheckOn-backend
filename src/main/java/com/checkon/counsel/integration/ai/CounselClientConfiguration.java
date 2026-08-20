package com.checkon.counsel.integration.ai;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CounselClientConfiguration {

	@Bean
	CounselClient counselClient(
		@Value("${checkon.ai.counsel.adapter-base-url:http://localhost:8081}") String baseUrl,
		@Value("${checkon.ai.counsel.drafts-path:/v1/counsel/drafts}") String draftsPath,
		@Value("${checkon.ai.counsel.connect-timeout:2s}") Duration connectTimeout,
		// Draft creation moved to Kafka (2026-08-20 Kafka-철회 reversal) -- this
		// timeout now only covers GET /v1/counsel/drafts/{jobId} and the refine
		// turn endpoint, neither of which runs a job inline anymore. The AI team
		// is re-reviewing what a tighter value should be; keep the old
		// conservative default until they confirm a number rather than guessing.
		@Value("${checkon.ai.counsel.read-timeout:500s}") Duration readTimeout
	) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return new HttpCounselClient(
			RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(),
			draftsPath
		);
	}
}
