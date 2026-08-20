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
		// POST /v1/counsel/drafts runs its own job to completion synchronously
		// (contract §0-3, K=1 as of 8/20 -- a pending job ahead of yours still
		// returns queued instead of being drained). Worst case per job: 5 LLM
		// calls x 90s = 450s (8/20: LLM call cap raised from 15s to 90s), so
		// the read timeout must stay at 480s or more (§0-4) or normal requests
		// get cut client-side while the job keeps running server-side.
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
