package com.checkon.report.integration.kafka;
import java.time.Clock; import java.time.Instant; import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.kafka.core.KafkaTemplate; import org.springframework.scheduling.annotation.Scheduled; import org.springframework.stereotype.Component;
import com.checkon.report.infrastructure.MonthlyReportRepository;
@Component @ConditionalOnProperty(prefix="checkon.ai.monthly-report.kafka",name="enabled",havingValue="true")
public class MonthlyReportOutboxPublisher {
	private final MonthlyReportRepository repository; private final KafkaTemplate<String,String> kafka; private final MonthlyReportKafkaProperties properties; private final Clock clock;
	public MonthlyReportOutboxPublisher(MonthlyReportRepository repository,KafkaTemplate<String,String> kafka,MonthlyReportKafkaProperties properties,Clock clock){this.repository=repository;this.kafka=kafka;this.properties=properties;this.clock=clock;}
	@Scheduled(fixedDelayString="${checkon.ai.monthly-report.kafka.publisher-delay:1000}") public void publish(){for(var event:repository.claimOutbox(Instant.now(clock),properties.batchSize())){
		try{kafka.send(properties.requestTopic(),event.tenantAlias(),event.payload()).get(properties.publishTimeout().toMillis(),TimeUnit.MILLISECONDS);repository.markOutboxPublished(event.id(),Instant.now(clock));}
		catch(InterruptedException e){Thread.currentThread().interrupt();repository.retryOutbox(event.id(),Instant.now(clock).plusSeconds(5),event.attempts()>=properties.maxAttempts());return;}
		catch(Exception e){repository.retryOutbox(event.id(),Instant.now(clock).plusSeconds(Math.min(60,event.attempts()*5L)),event.attempts()>=properties.maxAttempts());}
	}}
}
