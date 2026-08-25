package com.checkon.report.integration.kafka;
import java.time.Clock; import java.time.Instant; import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.kafka.annotation.KafkaListener; import org.springframework.stereotype.Component; import org.springframework.transaction.annotation.Transactional;
import com.checkon.detection.infrastructure.kafka.AiTenantAliasService; import com.checkon.report.infrastructure.MonthlyReportRepository; import com.fasterxml.jackson.databind.JsonNode; import com.fasterxml.jackson.databind.ObjectMapper;
@Component @ConditionalOnProperty(prefix="checkon.ai.monthly-report.kafka",name="enabled",havingValue="true")
public class MonthlyReportResultListener {
	private final MonthlyReportRepository repository; private final AiTenantAliasService tenants; private final ObjectMapper json; private final Clock clock;
	public MonthlyReportResultListener(MonthlyReportRepository repository,AiTenantAliasService tenants,ObjectMapper json,Clock clock){this.repository=repository;this.tenants=tenants;this.json=json;this.clock=clock;}
	@Transactional @KafkaListener(topics="${checkon.ai.monthly-report.kafka.result-topic}",groupId="${checkon.ai.monthly-report.kafka.consumer-group}")
	public void consume(String payload){try{JsonNode root=json.readTree(payload); if(!"monthly-report-result-1".equals(root.path("schema_version").asText()))throw new IllegalArgumentException("Unsupported monthly report result schema");
		UUID eventId=UUID.fromString(root.path("event_id").asText()),reportId=UUID.fromString(root.path("report_id").asText()); UUID teacher=tenants.requireTeacherId(root.path("tenant_alias").asText());
		String outcome=root.path("outcome").asText(); JsonNode ai=root.path("ai_response"); String error="failed".equals(outcome)?root.path("error_code").asText("AI_REPORT_FAILED"):null;
		String status=error==null?ai.path("data").path("status").asText():null; int blocks=error==null?ai.path("data").path("blocks").size():0;
		if(error==null&&!java.util.Set.of("ready","rejected_insufficient","template_only").contains(status))throw new IllegalArgumentException("Unsupported AI report status");
		repository.acceptResult(eventId,teacher,reportId,payload,status,error==null?json.writeValueAsString(ai):null,blocks,error,Instant.now(clock));
	}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Invalid monthly report result event",e);}}
}
