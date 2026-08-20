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
		// AI-A 2026-08-20 실측(배포 반영됨): GET은 POST와 같은 급 -- 콜드 터널
		// 왕복 최악 0.93초에 약 10배 여유를 둔 10초 권고. GET은 잡을 돌리지
		// 않고 현재 phase만 읽으므로 이 정도로 충분하다.
		@Value("${checkon.ai.counsel.get-read-timeout:10s}") Duration getReadTimeout,
		// refine은 여전히 동기 LLM 호출이다(배경 워커로 옮긴 건 생성뿐) --
		// 실측 8.17초, 최악은 콜당 90초 재생성까지 감안해 04 §1-1의 480초를
		// 그대로 유지한다. "일괄로 내리시면 refine이 터집니다"(AI팀 경고).
		@Value("${checkon.ai.counsel.refine-read-timeout:480s}") Duration refineReadTimeout
	) {
		return new HttpCounselClient(
			restClient(baseUrl, connectTimeout, getReadTimeout),
			restClient(baseUrl, connectTimeout, refineReadTimeout),
			draftsPath
		);
	}

	private static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}
}
