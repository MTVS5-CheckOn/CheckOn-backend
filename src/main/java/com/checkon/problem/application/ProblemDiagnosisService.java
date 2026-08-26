package com.checkon.problem.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkon.problem.application.ProblemStudioViews.DiagnosisProvenance;
import com.checkon.problem.application.ProblemStudioViews.GenerationCapability;
import com.checkon.problem.application.ProblemStudioViews.SkillNodeCandidate;
import com.checkon.problem.application.ProblemStudioViews.WeaknessAnalysis;
import com.checkon.problem.application.ProblemStudioViews.WeaknessCell;
import com.checkon.problem.domain.ProblemStudioEvaluation;
import com.checkon.problem.domain.ProblemTypeTag;
import com.checkon.problem.infrastructure.persistence.ProblemDiagnosisSnapshotRepository.Snapshot;
import com.checkon.problem.integration.ai.ProblemDiagnosisClient;
import com.checkon.problem.integration.ai.ProblemDiagnosisClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProblemDiagnosisService {
	private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
	private static final int MINIMUM_SAMPLE_SIZE=10;
	private static final List<GenerationCapability> CAPABILITIES=capabilities();
	private final ProblemDiagnosisTransactionService transactions;
	private final ProblemDiagnosisClient client;
	private final ObjectMapper objectMapper;

	public ProblemDiagnosisService(ProblemDiagnosisTransactionService transactions,ProblemDiagnosisClient client,ObjectMapper objectMapper) {
		this.transactions=transactions; this.client=client; this.objectMapper=objectMapper;
	}

	public WeaknessAnalysis diagnose(UUID teacherId,UUID studentId) {
		var prepared=transactions.prepare(teacherId,studentId);
		String response=null, failure=null;
		try {
			response=client.diagnose(prepared.requestPayload(),new ProblemDiagnosisClient.Headers(prepared.tenantAlias(),
				"diag_"+compact(prepared.id()),"diag_"+compact(prepared.id())));
		}
		catch (ProblemDiagnosisClientException exception) { failure=exception.httpStatus()==null?"ADAPTER_UNAVAILABLE":"ADAPTER_HTTP_"+exception.httpStatus(); }
		Snapshot snapshot=transactions.persist(prepared,response,failure);
		return view(prepared,snapshot);
	}

	private WeaknessAnalysis view(ProblemDiagnosisTransactionService.PreparedDiagnosis prepared,Snapshot snapshot) {
		List<WeaknessCell> cells="GENERATED".equals(snapshot.status())?aiCells(snapshot.responsePayload()):localCells(prepared);
		BigDecimal average=overall(prepared);
		DiagnosisProvenance provenance=new DiagnosisProvenance(snapshot.status(),snapshot.statusReason(),snapshot.snapshotHash(),
			snapshot.taxonomyVersion(),snapshot.graphVersion(),snapshot.configVersion());
		List<SkillNodeCandidate> nodes="GENERATED".equals(snapshot.status())?aiNodes(snapshot.responsePayload()):List.of();
		return new WeaknessAnalysis(snapshot.id(),prepared.studentId(),prepared.from().atZone(SEOUL).toLocalDate(),
			prepared.asOf().atZone(SEOUL).toLocalDate(),MINIMUM_SAMPLE_SIZE,average,cells,nodes,CAPABILITIES,provenance);
	}

	private List<SkillNodeCandidate> aiNodes(String payload) {
		try {
			JsonNode nodes=objectMapper.readTree(payload).get("data").get("weakness_map").get("nodes");
			List<SkillNodeCandidate> result=new ArrayList<>();
			nodes.properties().forEach(entry->{ JsonNode value=entry.getValue(); List<String> basis=new ArrayList<>();
				JsonNode rawBasis=value.get("basis"); if(rawBasis!=null&&rawBasis.isArray()) for(JsonNode item:rawBasis)
					if(item.isTextual()) basis.add(item.asText());
				result.add(new SkillNodeCandidate(entry.getKey(),text(value,"verdict"),List.copyOf(basis))); });
			return result.stream().sorted(java.util.Comparator.comparing(SkillNodeCandidate::skillNodeId)).toList();
		}
		catch (RuntimeException exception) { return List.of(); }
	}

	private static List<GenerationCapability> capabilities() {
		List<GenerationCapability> result=new ArrayList<>();
		for(String area:List.of("language","reading","literature","speech_writing","media"))
			for(ProblemTypeTag type:List.of(ProblemTypeTag.FACT,ProblemTypeTag.INFER,
				ProblemTypeTag.CRITIC,ProblemTypeTag.CONCEPT)) result.add(new GenerationCapability(area,type,20,3));
		return List.copyOf(result);
	}

	private List<WeaknessCell> aiCells(String payload) {
		try {
			JsonNode data=objectMapper.readTree(payload).get("data"); JsonNode grid=data.get("grid"); JsonNode values=grid.get("cells");
			List<WeaknessCell> result=new ArrayList<>();
			for(JsonNode cell:values) {
				int n=cell.get("n").asInt(); JsonNode accNode=cell.get("acc"); BigDecimal accuracy=accNode==null||accNode.isNull()?null:accNode.decimalValue().multiply(BigDecimal.valueOf(100)).setScale(1,RoundingMode.HALF_UP);
				String verdict=text(cell,"verdict"); ProblemStudioEvaluation evaluation="weak".equals(verdict)?ProblemStudioEvaluation.WEAK_SIGNAL:
					"ok".equals(verdict)?ProblemStudioEvaluation.GOOD:ProblemStudioEvaluation.ON_HOLD;
				int correct=accuracy==null?0:accuracy.multiply(BigDecimal.valueOf(n)).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP).intValue();
				result.add(new WeaknessCell(text(cell,"area_tag"),text(cell,"type_tag").toUpperCase(Locale.ROOT),n,correct,accuracy,null,evaluation));
			}
			return List.copyOf(result);
		}
		catch (RuntimeException exception) { return List.of(); }
	}

	private List<WeaknessCell> localCells(ProblemDiagnosisTransactionService.PreparedDiagnosis prepared) {
		record Counts(int solved,int correct) { Counts add(boolean value){return new Counts(solved+1,correct+(value?1:0));} }
		Map<String,Counts> counts=new LinkedHashMap<>();
		for(var event:prepared.events()) counts.compute(event.areaTag()+"\u0000"+event.typeTag(),(k,v)->(v==null?new Counts(0,0):v).add(event.correct()));
		BigDecimal average=overall(prepared); List<WeaknessCell> result=new ArrayList<>();
		counts.forEach((key,value)->{ String[] parts=key.split("\u0000",2); BigDecimal accuracy=percent(value.correct(),value.solved());
			ProblemStudioEvaluation evaluation=value.solved()<MINIMUM_SAMPLE_SIZE||average==null?ProblemStudioEvaluation.ON_HOLD:
				accuracy.compareTo(average)>=0?ProblemStudioEvaluation.GOOD:ProblemStudioEvaluation.WEAK_SIGNAL;
			result.add(new WeaknessCell(parts[0],parts[1].toUpperCase(Locale.ROOT),value.solved(),value.correct(),accuracy,
				average==null?null:accuracy.subtract(average).setScale(1,RoundingMode.HALF_UP),evaluation)); });
		return List.copyOf(result);
	}
	private BigDecimal overall(ProblemDiagnosisTransactionService.PreparedDiagnosis prepared) { int solved=prepared.events().size();
		int correct=(int)prepared.events().stream().filter(e->e.correct()).count(); return percent(correct,solved); }
	private static BigDecimal percent(int numerator,int denominator) { return denominator==0?null:BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),1,RoundingMode.HALF_UP); }
	private static String text(JsonNode node,String field){JsonNode v=node==null?null:node.get(field);return v!=null&&!v.isNull()?v.asText():null;}
	private static String compact(UUID value){return value.toString().replace("-","");}
}
