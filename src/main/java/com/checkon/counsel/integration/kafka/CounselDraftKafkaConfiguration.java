package com.checkon.counsel.integration.kafka;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@code @EnableKafka}/{@code @EnableKafkaRetryTopic} are already active globally via {@code RiskDetectionKafkaConfiguration}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CounselDraftKafkaProperties.class)
public class CounselDraftKafkaConfiguration {
}
