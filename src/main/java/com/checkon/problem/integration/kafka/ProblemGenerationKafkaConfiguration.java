package com.checkon.problem.integration.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProblemGenerationKafkaProperties.class)
public class ProblemGenerationKafkaConfiguration {
	@Bean("problemGenerationKafkaListenerContainerFactory")
	@ConditionalOnProperty(prefix = "checkon.ai.problem-generation.kafka", name = "enabled", havingValue = "true")
	ConcurrentKafkaListenerContainerFactory<String, String> problemGenerationKafkaListenerContainerFactory(
		ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate,
		ProblemGenerationKafkaProperties properties) {
		var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
		factory.setConsumerFactory(consumerFactory);
		var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
			(record, exception) -> new TopicPartition(properties.deadLetterTopic(), record.partition()));
		var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2L));
		errorHandler.addNotRetryableExceptions(ProblemGenerationEventContractException.class);
		factory.setCommonErrorHandler(errorHandler);
		return factory;
	}
}
