package com.checkon.report.integration.ai;
import java.net.http.HttpClient;import java.time.Duration;import org.springframework.beans.factory.annotation.Value;import org.springframework.context.annotation.*;import org.springframework.http.client.JdkClientHttpRequestFactory;import org.springframework.web.client.RestClient;
@Configuration(proxyBeanMethods=false) public class MonthlyReportRevisionClientConfiguration {
	@Bean MonthlyReportRevisionClient monthlyReportRevisionClient(@Value("${checkon.ai.monthly-report.adapter-base-url:http://localhost:8081}") String base,@Value("${checkon.ai.monthly-report.revision-read-timeout:1350s}") Duration timeout){var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(timeout);return new RestMonthlyReportRevisionClient(RestClient.builder().baseUrl(base).requestFactory(factory).build());}
}
