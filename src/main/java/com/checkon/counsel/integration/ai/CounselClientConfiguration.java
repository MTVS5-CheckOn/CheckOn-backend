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
		// POST /v1/counsel/drafts drains up to 3 queued jobs synchronously before
		// returning (§0-3). Worst case: 3 jobs x 5 LLM calls x 15s = 225s, so the
		// read timeout must stay at 240s or more (§0-4) or normal requests get cut
		// client-side while the job keeps running server-side.
		@Value("${checkon.ai.counsel.read-timeout:245s}") Duration readTimeout
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
