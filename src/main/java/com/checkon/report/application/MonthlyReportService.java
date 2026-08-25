package com.checkon.report.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.counsel.application.AiGuardianAliasService;
import com.checkon.detection.infrastructure.kafka.AiTenantAliasService;
import com.checkon.report.infrastructure.MonthlyReportRepository;
import com.checkon.report.infrastructure.MonthlyReportRepository.ReportView;
import com.checkon.report.integration.ai.MonthlyReportRevisionClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MonthlyReportService {
	private static final Pattern KEY=Pattern.compile("[A-Za-z0-9._:-]{8,200}");
	private static final Pattern HASH=Pattern.compile("sha256:[0-9a-f]{64}");
	private final MonthlyReportRepository reports; private final MonthlyReportIdGenerator ids;
	private final AiGuardianAliasService guardians; private final AiTenantAliasService tenants;
	private final ObjectMapper json; private final Clock clock;
	private final MonthlyReportRevisionClient revisions;
	public MonthlyReportService(MonthlyReportRepository reports,MonthlyReportIdGenerator ids,AiGuardianAliasService guardians,
		AiTenantAliasService tenants,ObjectMapper json,Clock clock,MonthlyReportRevisionClient revisions){this.reports=reports;this.ids=ids;this.guardians=guardians;this.tenants=tenants;this.json=json;this.clock=clock;this.revisions=revisions;}

	@Transactional
	public Creation create(UUID teacherId,UUID studentId,YearMonth month,String kind,String clientKey){
		requireTeacher(teacherId); if(studentId==null||month==null)throw MonthlyReportException.invalid("studentId and reportMonth are required");
		String key=key(clientKey); String normalizedKind=kind==null?"MONTHLY":kind.trim().toUpperCase(Locale.ROOT);
		if(!normalizedKind.equals("MONTHLY")&&!normalizedKind.equals("ON_DEMAND"))throw MonthlyReportException.invalid("reportKind must be MONTHLY or ON_DEMAND");
		var context=reports.findStudentContext(teacherId,studentId).orElseThrow(MonthlyReportException::notFound);
		String guardianRef=guardians.getOrCreate(teacherId,studentId);
		LocalDate from=month.atDay(1),to=month.atEndOfMonth();
		var diagnosis=reports.latestDiagnosis(teacherId,studentId,to).orElseThrow(()->MonthlyReportException.invalid("A generated diagnosis is required before report creation"));
		var itemResults=reports.itemResults(teacherId,studentId,from,to);
		if(itemResults.isEmpty())throw MonthlyReportException.invalid("At least one graded item result is required for the report month");
		String source=source(diagnosis.payload(),itemResults); String requestHash=sha256(source);
		var existing=reports.findByClientKey(teacherId,key);
		if(existing.isPresent()){
			if(!existing.get().hash().equals(requestHash))throw MonthlyReportException.conflict("Idempotency-Key was already used with a different report snapshot");
			return new Creation(existing.get().id(),true,existing.get().status());
		}
		var generated=ids.nextIds(2); UUID reportId=generated.get(0),eventId=generated.get(1); Instant now=Instant.now(clock);
		String tenant=tenants.getOrCreate(teacherId);
		Map<String,Object> envelope=new LinkedHashMap<>(); envelope.put("event_id",eventId); envelope.put("event_type","monthly_report.requested");
		envelope.put("schema_version","monthly-report-request-1"); envelope.put("tenant_alias",tenant); envelope.put("request_id",reportId);
		envelope.put("report_id",reportId); envelope.put("guardian_ref",guardianRef); envelope.put("request_hash",requestHash);
		envelope.put("payload",Map.of("report_id",reportId,"guardian_ref",guardianRef,"source",read(source)));
		reports.insert(new MonthlyReportRepository.ReportInsert(reportId,teacherId,studentId,context.classId(),guardianRef,from,normalizedKind,key,requestHash,source,tenant,now),eventId,write(envelope));
		return new Creation(reportId,false,"REQUESTED");
	}

	@Transactional(readOnly=true)
	public List<ReportView> list(UUID teacherId,YearMonth month,String status,String query,int page,int size){
		requireTeacher(teacherId); if(page<0||size<1||size>100)throw MonthlyReportException.invalid("page must be >= 0 and size must be 1..100");
		return reports.list(teacherId,(month==null?YearMonth.now(clock):month).atDay(1),text(status),text(query),size,page*size);
	}
	@Transactional(readOnly=true) public ReportView get(UUID teacherId,UUID reportId){requireTeacher(teacherId);return reports.findView(teacherId,reportId).orElseThrow(MonthlyReportException::notFound);}

	@Transactional
	public ReportView registerArtifact(UUID teacherId,UUID reportId,int revision,String storageKey,String hash,int pages){
		var report=get(teacherId,reportId); if(!"ready".equals(report.aiStatus()))throw MonthlyReportException.conflict("Only AI ready reports can receive a delivery artifact");
		if(revision<0||storageKey==null||storageKey.isBlank()||storageKey.length()>500||!HASH.matcher(hash==null?"":hash).matches()||pages<1||pages>100)
			throw MonthlyReportException.invalid("revision, storageKey, sha256 and pageCount are invalid");
		reports.addArtifact(ids.nextIds(1).getFirst(),teacherId,reportId,revision,storageKey.trim(),hash,pages,Instant.now(clock)); return get(teacherId,reportId);
	}
	@Transactional public ReportView updateBlock(UUID teacherId,UUID reportId,String blockId,int baseRevision,String content){var current=mutable(teacherId,reportId);if(blockId==null||blockId.isBlank()||content==null||content.isBlank())throw MonthlyReportException.invalid("blockId and content are required");String response=revisions.update(tenants.getOrCreate(teacherId),reportId,blockId,baseRevision,content);storeRevision(teacherId,reportId,response);return get(teacherId,reportId);}
	@Transactional public ReportView restoreBlock(UUID teacherId,UUID reportId,String blockId,int baseRevision,int revert){mutable(teacherId,reportId);String response=revisions.restore(tenants.getOrCreate(teacherId),reportId,blockId,baseRevision,revert,"teacher:"+teacherId);storeRevision(teacherId,reportId,response);return get(teacherId,reportId);}
	private ReportView mutable(UUID teacherId,UUID reportId){var value=get(teacherId,reportId);if(!"NOT_SENT".equals(value.deliveryStatus()))throw MonthlyReportException.conflict("A queued or delivered report is read-only");return value;}
	private void storeRevision(UUID teacherId,UUID reportId,String response){JsonNode root=read(response);String status=root.path("data").path("status").asText();if(!java.util.Set.of("ready","rejected_insufficient","template_only").contains(status))throw MonthlyReportException.conflict("Adapter returned an invalid report revision");reports.updateAiPayload(teacherId,reportId,response,root.path("data").path("blocks").size(),Instant.now(clock));}

	@Transactional
	public List<DeliveryResult> queueDeliveries(UUID teacherId,List<UUID> reportIds,String clientKey){
		requireTeacher(teacherId); String key=key(clientKey); if(reportIds==null||reportIds.isEmpty()||reportIds.size()>100)throw MonthlyReportException.invalid("reportIds must contain 1..100 entries");
		if(reportIds.stream().distinct().count()!=reportIds.size())throw MonthlyReportException.invalid("reportIds must not contain duplicates");
		String requestHash=sha256(write(reportIds.stream().map(UUID::toString).sorted().toList())); List<UUID> generated=ids.nextIds(reportIds.size());
		List<DeliveryResult> result=new ArrayList<>(); Instant now=Instant.now(clock);
		for(int i=0;i<reportIds.size();i++){
			UUID reportId=reportIds.get(i);
			try{var target=reports.deliveryTarget(teacherId,reportId); if(!"ready".equals(target.aiStatus()))throw MonthlyReportException.conflict("AI status is not ready");
				if(target.parentId()==null)throw MonthlyReportException.conflict("An active parent recipient is required");
				if(target.artifactId()==null)throw MonthlyReportException.conflict("A ready PDF artifact is required");
				reports.queueDelivery(generated.get(i),teacherId,target,key,requestHash,now); result.add(new DeliveryResult(reportId,"QUEUED",null));}
			catch(RuntimeException exception){result.add(new DeliveryResult(reportId,"REJECTED",safeReason(exception)));}
		} return List.copyOf(result);
	}

	private String source(String diagnosisPayload,List<MonthlyReportRepository.ItemResult> items){
		JsonNode root=read(diagnosisPayload),data=root.path("data"); if(!data.path("weakness_map").isObject())throw MonthlyReportException.invalid("Stored diagnosis has no weakness_map");
		List<Map<String,Object>> results=items.stream().map(item->{Map<String,Object> value=new LinkedHashMap<>(); value.put("event_id",item.id().toString());value.put("area_tag",item.areaTag());value.put("type_tag",item.typeTag());value.put("skill_node_id",item.skillNodeId());value.put("chosen_no",item.chosenNo());value.put("correct_no",item.correctNo());value.put("correct",item.correct());if(item.misconceptionTag()!=null)value.put("misconception_tag",item.misconceptionTag());value.put("occurred_at",item.occurredAt());return value;}).toList();
		long correct=items.stream().filter(MonthlyReportRepository.ItemResult::correct).count(); Map<String,Object> source=new LinkedHashMap<>();
		source.put("weakness_map",data.path("weakness_map")); source.put("misconceptions",data.path("misconceptions").isMissingNode()?List.of():data.path("misconceptions"));
		source.put("item_results",results); source.put("metrics",Map.of("total_items",items.size(),"correct_items",correct,"accuracy",(double)correct/items.size())); source.put("cell_min_items",1); return write(source);
	}
	private JsonNode read(String value){try{return json.readTree(value);}catch(JacksonException e){throw MonthlyReportException.invalid("Stored report source JSON is invalid");}}
	private String write(Object value){try{return json.writeValueAsString(value);}catch(JacksonException e){throw new IllegalStateException("Monthly report JSON serialization failed",e);}}
	private static String sha256(String value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
	private static String key(String value){if(value==null||!KEY.matcher(value.trim()).matches())throw MonthlyReportException.invalid("Idempotency-Key must be 8..200 safe ASCII characters");return value.trim();}
	private static String text(String value){return value==null?"":value.trim();} private static void requireTeacher(UUID id){if(id==null)throw MonthlyReportException.invalid("Authenticated teacher is required");}
	private static String safeReason(RuntimeException e){return e instanceof MonthlyReportException m?m.getMessage():"REPORT_NOT_SENDABLE";}
	public record Creation(UUID reportId,boolean replayed,String status){} public record DeliveryResult(UUID reportId,String status,String reason){}
}
