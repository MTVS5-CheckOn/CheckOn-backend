package com.checkon.counsel.integration.ai;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ClassifyClientConfiguration {

	@Bean
	ClassifyClient classifyClient(
		@Value("${checkon.ai.classify.adapter-base-url:http://localhost:8081}") String baseUrl,
		@Value("${checkon.ai.classify.classify-path:/v1/classify}") String classifyPath,
		@Value("${checkon.ai.classify.confirmations-path:/v1/confirmations}") String confirmationsPath,
		@Value("${checkon.ai.classify.connect-timeout:2s}") Duration connectTimeout,
		// classify/confirmations are single synchronous LLM-or-cache calls, not
		// counsel's up-to-3-job drain, so a much shorter read timeout is enough.
		@Value("${checkon.ai.classify.read-timeout:30s}") Duration readTimeout
	) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return new HttpClassifyClient(
			RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(),
			classifyPath,
			confirmationsPath
		);
	}
}
