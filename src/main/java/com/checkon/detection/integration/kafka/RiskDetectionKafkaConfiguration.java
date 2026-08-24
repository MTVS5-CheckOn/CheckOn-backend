package com.checkon.detection.integration.kafka;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableKafkaRetryTopic
@EnableConfigurationProperties(RiskDetectionKafkaProperties.class)
public class RiskDetectionKafkaConfiguration {
}
