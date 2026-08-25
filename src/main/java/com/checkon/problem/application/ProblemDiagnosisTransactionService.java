package com.checkon.problem.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.learning.application.AiStudentAliasService;
import com.checkon.problem.infrastructure.persistence.ProblemDiagnosisSnapshotRepository;
import com.checkon.problem.infrastructure.persistence.ProblemDiagnosisSnapshotRepository.Snapshot;
import com.checkon.roster.domain.RelationshipStatus;
import com.checkon.roster.infrastructure.persistence.TeacherStudentRelationshipRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProblemDiagnosisTransactionService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private final ProblemDiagnosisSnapshotRepository repository;
	private final TeacherStudentRelationshipRepository relationships;
	private final AiStudentAliasService studentAliases;
	private final AiProblemAliasService problemAliases;
	private final ProblemGenerationIdGenerator ids;
	private final ProblemGenerationPayloadHasher hasher;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ProblemDiagnosisTransactionService(ProblemDiagnosisSnapshotRepository repository,
		TeacherStudentRelationshipRepository relationships, AiStudentAliasService studentAliases,
		AiProblemAliasService problemAliases, ProblemGenerationIdGenerator ids,
		ProblemGenerationPayloadHasher hasher, TeacherTenantDatabaseContext tenantContext,
		ObjectMapper objectMapper, Clock clock) {
		this.repository=repository; this.relationships=relationships; this.studentAliases=studentAliases;
		this.problemAliases=problemAliases; this.ids=ids; this.hasher=hasher; this.tenantContext=tenantContext;
		this.objectMapper=objectMapper; this.clock=clock;
	}

	@Transactional
	public PreparedDiagnosis prepare(UUID teacherId, UUID studentId) {
		Objects.requireNonNull(studentId,"studentId must not be null");
		tenantContext.setCurrentTeacher(teacherId);
		if (!relationships.existsByTeacherIdAndStudentIdAndStatus(teacherId,studentId,RelationshipStatus.ACTIVE))
			throw ProblemGenerationException.inaccessibleTarget();
		Instant asOf=Instant.now(clock);
		Instant from=asOf.minus(Duration.ofDays(56));
		String studentRef=studentAliases.getOrCreate(teacherId,studentId);
		String tenantAlias=problemAliases.getOrCreateTenantAlias(teacherId);
		List<ProblemDiagnosisSnapshotRepository.LearningEvent> records=repository.findEvents(teacherId,studentId,from,asOf);
		List<Map<String,Object>> events=new ArrayList<>();
		for (var record:records) {
			LinkedHashMap<String,Object> event=new LinkedHashMap<>();
			event.put("event_id","lr_"+compact(record.id())); event.put("area_tag",record.areaTag().trim().toLowerCase(Locale.ROOT));
			event.put("type_tag",record.typeTag()); event.put("correct",record.correct());
			event.put("occurred_at",record.occurredAt().toString()); event.put("tag_confirmed",true); event.put("skill_node_id",null);
			events.add(event);
		}
		for (var response:repository.findProblemResponses(teacherId,studentId,from,asOf)) {
			LinkedHashMap<String,Object> event=new LinkedHashMap<>();
			event.put("event_id","problem-response_"+compact(response.id()));
			event.put("area_tag",response.areaTag()); event.put("type_tag",response.typeTag());
			event.put("item_format","mcq"); event.put("chosen_no",response.chosenNo());
			event.put("correct_no",response.correctNo()); event.put("correct",response.correct());
			event.put("skill_node_id",response.skillNodeId());
			if(!response.correct()) event.put("misconception_tag",response.misconceptionTag());
			event.put("occurred_at",response.occurredAt().toString()); event.put("tag_confirmed",true);
			events.add(event);
		}
		events.sort(java.util.Comparator
			.comparing((Map<String,Object> event)->String.valueOf(event.get("occurred_at")))
			.thenComparing(event->String.valueOf(event.get("event_id"))));
		LinkedHashMap<String,Object> payload=new LinkedHashMap<>();
		payload.put("student_ref",studentRef);
		payload.put("period",Map.of("from_date",localDate(from).toString(),"to_date",localDate(asOf).toString()));
		payload.put("as_of",asOf.toString()); payload.put("events",events);
		String hash=hasher.sha256(write(payload)); payload.put("snapshot_hash",hash);
		UUID diagnosisId=ids.nextIds(1).getFirst();
		return new PreparedDiagnosis(diagnosisId,teacherId,studentId,studentRef,tenantAlias,hash,from,asOf,
			write(payload),List.copyOf(records));
	}

	@Transactional
	public Snapshot persist(PreparedDiagnosis prepared, String responsePayload, String unavailableReason) {
		tenantContext.setCurrentTeacher(prepared.teacherId());
		Instant now=Instant.now(clock);
		String status="UNAVAILABLE", reason=unavailableReason, taxonomy=null, graph=null, config=null;
		String storedResponse=responsePayload;
		if (responsePayload!=null) {
			try {
				JsonNode data=objectMapper.readTree(responsePayload).get("data");
				String aiStatus=text(data,"status"); reason=text(data,"status_reason");
				status="generated".equals(aiStatus)?"GENERATED":"REJECTED_INSUFFICIENT";
				JsonNode map=data==null?null:data.get("weakness_map");
				if (map!=null && map.isObject()) { taxonomy=text(map,"taxonomy_version"); graph=text(map,"graph_version"); config=text(map,"config_version");
					if(!prepared.snapshotHash().equals(text(map,"snapshot_hash"))) throw new IllegalArgumentException("snapshot_hash mismatch"); }
			}
			catch (RuntimeException exception) {
				status="UNAVAILABLE"; reason="INVALID_DIAGNOSIS_RESPONSE"; storedResponse=null;
			}
		}
		Snapshot snapshot=new Snapshot(prepared.id(),prepared.teacherId(),prepared.studentId(),prepared.studentRef(),status,reason,
			prepared.snapshotHash(),taxonomy,graph,config,prepared.requestPayload(),storedResponse,prepared.asOf(),now);
		repository.insert(snapshot);
		return snapshot;
	}

	@Transactional(readOnly = true)
	public Snapshot requireGenerated(UUID teacherId, UUID studentId, UUID diagnosisId) {
		tenantContext.setCurrentTeacher(teacherId);
		Snapshot snapshot=repository.find(diagnosisId,teacherId,studentId).orElseThrow(ProblemGenerationException::notFound);
		if (!"GENERATED".equals(snapshot.status()))
			throw ProblemGenerationException.invalidState("the selected diagnosis is not generated");
		return snapshot;
	}

	private LocalDate localDate(Instant value) { return value.atZone(SEOUL).toLocalDate(); }
	private String write(Object value) { try { return objectMapper.writeValueAsString(value); }
		catch (JacksonException e) { throw new IllegalStateException("problem diagnosis JSON serialization failed",e); } }
	private static String text(JsonNode node,String field) { if(node==null)return null; JsonNode v=node.get(field);
		return v!=null&&v.isTextual()&&!v.asText().isBlank()?v.asText():null; }
	private static String compact(UUID value) { return value.toString().replace("-",""); }

	public record PreparedDiagnosis(UUID id,UUID teacherId,UUID studentId,String studentRef,String tenantAlias,
		String snapshotHash,Instant from,Instant asOf,String requestPayload,
		List<ProblemDiagnosisSnapshotRepository.LearningEvent> events) { }
}
