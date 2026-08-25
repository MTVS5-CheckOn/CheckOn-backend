package com.checkon.problem.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.problem.infrastructure.persistence.ProblemAssignmentResponseRepository;
import com.checkon.problem.infrastructure.persistence.ProblemAssignmentResponseRepository.NewResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProblemAssignmentResponseService {
	private final ProblemAssignmentResponseRepository responses;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ProblemAssignmentResponseService(ProblemAssignmentResponseRepository responses,
		TeacherTenantDatabaseContext tenantContext,ObjectMapper objectMapper,Clock clock) {
		this.responses=responses; this.tenantContext=tenantContext; this.objectMapper=objectMapper; this.clock=clock;
	}

	@Transactional
	public ResponseView record(UUID teacherId,UUID studentId,UUID assignmentId,UUID itemId,
		int chosenNo,Instant respondedAt) {
		if(teacherId==null) throw ProblemGenerationException.invalidPrincipal();
		if(studentId==null||assignmentId==null||itemId==null||chosenNo<1||chosenNo>5)
			throw ProblemGenerationException.invalidRequest("assignment, item, student, and 1-based chosenNo are required");
		tenantContext.setCurrentTeacher(teacherId);
		var source=responses.findGradingSource(teacherId,studentId,assignmentId,itemId)
			.orElseThrow(ProblemGenerationException::notFound);
		Grading grading=grading(source.itemSnapshot(),chosenNo);
		Instant occurred=respondedAt==null?Instant.now(clock):respondedAt;
		boolean inserted=responses.insert(new NewResponse(teacherId,assignmentId,studentId,source.setId(),itemId,
			chosenNo,grading.correctNo(),grading.correct(),grading.areaTag(),grading.typeTag(),
			grading.skillNodeId(),grading.misconceptionTag(),
			occurred,Instant.now(clock)));
		if(!inserted) {
			var existing=responses.find(teacherId,assignmentId,itemId).orElseThrow();
			if(existing.chosenNo()!=chosenNo)
				throw ProblemGenerationException.invalidState("an immutable assignment response already exists");
			return new ResponseView(chosenNo,existing.correctNo(),existing.correct(),existing.skillNodeId(),
				existing.misconceptionTag(),existing.respondedAt(),true);
		}
		return new ResponseView(chosenNo,grading.correctNo(),grading.correct(),grading.skillNodeId(),
			grading.misconceptionTag(),occurred,false);
	}

	private Grading grading(String snapshot,int chosenNo) {
		try {
			var root=objectMapper.readTree(snapshot); var correctNode=root.get("correctNo");
			String skillNodeId=text(root,"skillNodeId"); String areaTag=text(root,"areaTag"); String typeTag=text(root,"typeTag");
			if(correctNode==null||!correctNode.canConvertToInt()||correctNode.asInt()<1||correctNode.asInt()>5
				||skillNodeId==null||areaTag==null||typeTag==null)
				throw ProblemGenerationException.invalidState("saved item has no grading contract");
			int correctNo=correctNode.asInt(); boolean correct=chosenNo==correctNo; String misconception=null;
			if(!correct) {
				var options=root.get("options"); if(options!=null&&options.isArray()) for(var option:options)
					if(option.get("position")!=null&&option.get("position").asInt()==chosenNo)
						misconception=text(option,"misconceptionTag");
				if(misconception==null) throw ProblemGenerationException.invalidState(
					"the selected wrong choice has no misconception tag");
			}
			return new Grading(correctNo,correct,areaTag,typeTag,skillNodeId,misconception);
		}
		catch(JacksonException exception) { throw ProblemGenerationException.invalidState("saved item snapshot is invalid"); }
	}
	private static String text(tools.jackson.databind.JsonNode node,String field) { var value=node.get(field);
		return value!=null&&value.isTextual()&&!value.asText().isBlank()?value.asText():null; }

	private record Grading(int correctNo,boolean correct,String areaTag,String typeTag,
		String skillNodeId,String misconceptionTag) { }
	public record ResponseView(int chosenNo,int correctNo,boolean correct,String skillNodeId,
		String misconceptionTag,Instant respondedAt,boolean replayed) { }
}
