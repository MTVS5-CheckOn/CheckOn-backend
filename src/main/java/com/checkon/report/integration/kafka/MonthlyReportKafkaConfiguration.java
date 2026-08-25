package com.checkon.report.integration.kafka;
import org.springframework.boot.context.properties.EnableConfigurationProperties; import org.springframework.context.annotation.Configuration;
@Configuration @EnableConfigurationProperties(MonthlyReportKafkaProperties.class) public class MonthlyReportKafkaConfiguration {}
