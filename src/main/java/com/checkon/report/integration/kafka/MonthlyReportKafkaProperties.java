package com.checkon.report.integration.kafka;
import java.time.Duration; import org.springframework.boot.context.properties.ConfigurationProperties; import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("checkon.ai.monthly-report.kafka") public record MonthlyReportKafkaProperties(boolean enabled,
	@DefaultValue("checkon.ai.monthly-report.requests.v1") String requestTopic,@DefaultValue("checkon.ai.monthly-report.results.v1") String resultTopic,
	@DefaultValue("checkon-backend-monthly-report-v1") String consumerGroup,@DefaultValue("20") int batchSize,@DefaultValue("5") int maxAttempts,
	@DefaultValue("10s") Duration publishTimeout){public MonthlyReportKafkaProperties{if(batchSize<1||batchSize>100||maxAttempts<1||maxAttempts>20)throw new IllegalArgumentException("Invalid monthly report Kafka bounds");}}
