package com.checkon.report.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MonthlyReportRepository {
	private final JdbcClient jdbc;
	public MonthlyReportRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

	public Optional<StudentContext> findStudentContext(UUID teacherId, UUID studentId) {
		return jdbc.sql("""
			SELECT student.id, COALESCE(NULLIF(personal.student_name,''), student.display_alias) student_name,
			       class_group.id class_id, class_group.name class_name, guardian.alias guardian_ref,
			       parent_student.parent_id
			FROM teacher_student_relationships relation
			JOIN student_profiles student ON student.id=relation.student_id
			LEFT JOIN student_personal_information personal ON personal.student_id=student.id
			LEFT JOIN class_enrollments enrollment ON enrollment.teacher_id=relation.teacher_id
			 AND enrollment.student_id=student.id AND enrollment.status IN ('ACTIVE','PAUSED')
			LEFT JOIN class_groups class_group ON class_group.id=enrollment.class_group_id
			JOIN ai_guardian_aliases guardian ON guardian.teacher_id=relation.teacher_id AND guardian.student_id=student.id
			LEFT JOIN parent_student_relationships parent_student ON parent_student.student_id=student.id AND parent_student.status='ACTIVE'
			LEFT JOIN parent_teacher_relationships parent_teacher ON parent_teacher.parent_id=parent_student.parent_id
			 AND parent_teacher.teacher_id=relation.teacher_id AND parent_teacher.status='ACTIVE'
			WHERE relation.teacher_id=:teacherId AND relation.student_id=:studentId
			  AND relation.status IN ('ACTIVE','PAUSED')
			""").param("teacherId",teacherId).param("studentId",studentId).query((rs,n)->new StudentContext(
			rs.getObject("id",UUID.class),rs.getString("student_name"),rs.getObject("class_id",UUID.class),
			rs.getString("class_name"),rs.getString("guardian_ref"),rs.getObject("parent_id",UUID.class))).optional();
	}

	public Optional<Diagnosis> latestDiagnosis(UUID teacherId, UUID studentId, LocalDate monthEnd) {
		return jdbc.sql("""
			SELECT response_payload::text payload, snapshot_hash
			FROM problem_diagnosis_snapshots
			WHERE teacher_id=:teacherId AND student_id=:studentId AND status='GENERATED'
			  AND diagnosed_at < (:monthEnd::date + interval '1 day')
			ORDER BY diagnosed_at DESC,id DESC LIMIT 1
			""").param("teacherId",teacherId).param("studentId",studentId).param("monthEnd",monthEnd)
			.query((rs,n)->new Diagnosis(rs.getString("payload"),rs.getString("snapshot_hash"))).optional();
	}

	public List<ItemResult> itemResults(UUID teacherId, UUID studentId, LocalDate from, LocalDate to) {
		return jdbc.sql("""
			SELECT id, area_tag, type_tag, skill_node_id, chosen_no, correct_no, correct,
			       misconception_tag, responded_at
			FROM problem_assignment_responses
			WHERE teacher_id=:teacherId AND student_id=:studentId
			  AND responded_at>=:from::date AND responded_at<(:to::date + interval '1 day')
			ORDER BY responded_at,id
			""").param("teacherId",teacherId).param("studentId",studentId).param("from",from).param("to",to)
			.query((rs,n)->new ItemResult(rs.getObject("id",UUID.class),rs.getString("area_tag"),rs.getString("type_tag"),
				rs.getString("skill_node_id"),rs.getInt("chosen_no"),rs.getInt("correct_no"),rs.getBoolean("correct"),
				rs.getString("misconception_tag"),rs.getObject("responded_at",OffsetDateTime.class).toInstant())).list();
	}

	public Optional<ReportRow> findByClientKey(UUID teacherId,String key) {
		return jdbc.sql("SELECT * FROM monthly_reports WHERE teacher_id=:teacherId AND client_idempotency_key=:key")
			.param("teacherId",teacherId).param("key",key).query(MonthlyReportRepository::row).optional();
	}

	public void insert(ReportInsert report, UUID eventId, String eventPayload) {
		jdbc.sql("""
			INSERT INTO monthly_reports(id,teacher_id,student_id,class_group_id,guardian_ref,report_month,report_kind,
			 client_idempotency_key,request_hash,source_payload,created_at,updated_at)
			VALUES(:id,:teacherId,:studentId,:classId,:guardianRef,:month,:kind,:clientKey,:hash,CAST(:source AS jsonb),:now,:now)
			""").param("id",report.id()).param("teacherId",report.teacherId()).param("studentId",report.studentId())
			.param("classId",nullable(report.classId())).param("guardianRef",report.guardianRef()).param("month",report.month())
			.param("kind",report.kind()).param("clientKey",report.clientKey()).param("hash",report.hash())
			.param("source",report.source()).param("now",time(report.now())).update();
		jdbc.sql("""
			INSERT INTO monthly_report_outbox(id,teacher_id,report_id,event_type,schema_version,tenant_alias,payload,next_attempt_at,created_at)
			VALUES(:id,:teacherId,:reportId,'monthly_report.requested','monthly-report-request-1',:tenant,CAST(:payload AS jsonb),:now,:now)
			""").param("id",eventId).param("teacherId",report.teacherId()).param("reportId",report.id())
			.param("tenant",report.tenantAlias()).param("payload",eventPayload).param("now",time(report.now())).update();
	}

	public List<OutboxEvent> claimOutbox(Instant now,int limit){
		return jdbc.sql("""
			WITH due AS (SELECT id FROM monthly_report_outbox WHERE status='PENDING' AND next_attempt_at<=:now
			 ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT :limit)
			UPDATE monthly_report_outbox event SET status='PUBLISHING',publish_attempts=publish_attempts+1
			FROM due WHERE event.id=due.id RETURNING event.id,event.teacher_id,event.report_id,event.tenant_alias,event.payload::text,event.publish_attempts
			""").param("now",time(now)).param("limit",limit).query((rs,n)->new OutboxEvent(rs.getObject("id",UUID.class),
			rs.getObject("teacher_id",UUID.class),rs.getObject("report_id",UUID.class),rs.getString("tenant_alias"),rs.getString("payload"),rs.getInt("publish_attempts"))).list();
	}
	public void markOutboxPublished(UUID id,Instant now){jdbc.sql("UPDATE monthly_report_outbox SET status='PUBLISHED',published_at=:now WHERE id=:id AND status='PUBLISHING'").param("now",time(now)).param("id",id).update();}
	public void retryOutbox(UUID id,Instant next,boolean dead){jdbc.sql("UPDATE monthly_report_outbox SET status=:status,next_attempt_at=:next WHERE id=:id AND status='PUBLISHING'")
		.param("status",dead?"FAILED":"PENDING").param("next",time(next)).param("id",id).update();}

	public List<ReportView> list(UUID teacherId, LocalDate month, String status, String query, int limit, int offset) {
		return jdbc.sql("""
			SELECT report.*, COALESCE(NULLIF(personal.student_name,''),student.display_alias) student_name,
			 class_group.name class_name,
			 CASE WHEN parent_student.parent_id IS NULL THEN 'missing' ELSE 'connected' END recipient_status,
			 artifact.id artifact_id,artifact.page_count,artifact.sha256 artifact_hash,
			 COALESCE((SELECT delivery.status FROM monthly_report_deliveries delivery WHERE delivery.report_id=report.id ORDER BY queued_at DESC LIMIT 1),'NOT_SENT') delivery_status
			FROM monthly_reports report JOIN student_profiles student ON student.id=report.student_id
			LEFT JOIN student_personal_information personal ON personal.student_id=student.id
			LEFT JOIN class_groups class_group ON class_group.id=report.class_group_id
			LEFT JOIN parent_student_relationships parent_student ON parent_student.student_id=report.student_id AND parent_student.status='ACTIVE'
			LEFT JOIN LATERAL (SELECT * FROM monthly_report_artifacts a WHERE a.report_id=report.id AND a.status='READY' ORDER BY revision_no DESC LIMIT 1) artifact ON true
			WHERE report.teacher_id=:teacherId AND report.report_month=:month
			 AND (:status='' OR COALESCE(report.ai_status,lower(report.request_status))=:status)
			 AND (:query='' OR lower(COALESCE(personal.student_name,student.display_alias)) LIKE '%'||lower(:query)||'%'
			      OR lower(COALESCE(class_group.name,'')) LIKE '%'||lower(:query)||'%')
			ORDER BY report.updated_at DESC,report.id DESC LIMIT :limit OFFSET :offset
			""").param("teacherId",teacherId).param("month",month).param("status",status).param("query",query)
			.param("limit",limit).param("offset",offset).query(MonthlyReportRepository::view).list();
	}

	public Optional<ReportView> findView(UUID teacherId, UUID reportId) {
		return jdbc.sql("""
			SELECT report.*, COALESCE(NULLIF(personal.student_name,''),student.display_alias) student_name,
			 class_group.name class_name,
			 CASE WHEN parent_student.parent_id IS NULL THEN 'missing' ELSE 'connected' END recipient_status,
			 artifact.id artifact_id,artifact.page_count,artifact.sha256 artifact_hash,
			 COALESCE((SELECT delivery.status FROM monthly_report_deliveries delivery WHERE delivery.report_id=report.id ORDER BY queued_at DESC LIMIT 1),'NOT_SENT') delivery_status
			FROM monthly_reports report JOIN student_profiles student ON student.id=report.student_id
			LEFT JOIN student_personal_information personal ON personal.student_id=student.id
			LEFT JOIN class_groups class_group ON class_group.id=report.class_group_id
			LEFT JOIN parent_student_relationships parent_student ON parent_student.student_id=report.student_id AND parent_student.status='ACTIVE'
			LEFT JOIN LATERAL (SELECT * FROM monthly_report_artifacts a WHERE a.report_id=report.id AND a.status='READY' ORDER BY revision_no DESC LIMIT 1) artifact ON true
			WHERE report.teacher_id=:teacherId AND report.id=:reportId
			""").param("teacherId",teacherId).param("reportId",reportId).query(MonthlyReportRepository::view).optional();
	}

	public boolean acceptResult(UUID eventId,UUID teacherId,UUID reportId,String payload,String status,String aiPayload,int blocks,String error,Instant now) {
		int inserted=jdbc.sql("""
			INSERT INTO monthly_report_result_inbox(event_id,teacher_id,report_id,event_payload,received_at)
			VALUES(:eventId,:teacherId,:reportId,CAST(:payload AS jsonb),:now) ON CONFLICT DO NOTHING
			""").param("eventId",eventId).param("teacherId",teacherId).param("reportId",reportId)
			.param("payload",payload).param("now",time(now)).update();
		if(inserted==0) return false;
		if(error==null) jdbc.sql("""
			UPDATE monthly_reports SET request_status='SUCCEEDED',ai_status=:status,ai_payload=CAST(:aiPayload AS jsonb),
			 block_count=:blocks,completed_at=:now,updated_at=:now WHERE id=:reportId AND teacher_id=:teacherId AND request_status='REQUESTED'
			""").param("status",status).param("aiPayload",aiPayload).param("blocks",blocks).param("now",time(now))
			.param("reportId",reportId).param("teacherId",teacherId).update();
		else jdbc.sql("UPDATE monthly_reports SET request_status='FAILED',error_code=:error,completed_at=:now,updated_at=:now WHERE id=:reportId AND teacher_id=:teacherId AND request_status='REQUESTED'")
			.param("error",error).param("now",time(now)).param("reportId",reportId).param("teacherId",teacherId).update();
		return true;
	}
	public void updateAiPayload(UUID teacherId,UUID reportId,String aiPayload,int blocks,Instant now){jdbc.sql("UPDATE monthly_reports SET ai_payload=CAST(:payload AS jsonb),block_count=:blocks,updated_at=:now WHERE teacher_id=:teacherId AND id=:reportId AND request_status='SUCCEEDED'")
		.param("payload",aiPayload).param("blocks",blocks).param("now",time(now)).param("teacherId",teacherId).param("reportId",reportId).update();}

	public void addArtifact(UUID id,UUID teacherId,UUID reportId,int revision,String storage,String hash,int pages,Instant now) {
		jdbc.sql("UPDATE monthly_report_artifacts SET status='SUPERSEDED' WHERE report_id=:reportId AND teacher_id=:teacherId AND status='READY'")
			.param("reportId",reportId).param("teacherId",teacherId).update();
		jdbc.sql("""
			INSERT INTO monthly_report_artifacts(id,teacher_id,report_id,revision_no,storage_key,sha256,page_count,status,created_at)
			VALUES(:id,:teacherId,:reportId,:revision,:storage,:hash,:pages,'READY',:now)
			""").param("id",id).param("teacherId",teacherId).param("reportId",reportId).param("revision",revision)
			.param("storage",storage).param("hash",hash).param("pages",pages).param("now",time(now)).update();
	}

	public DeliveryTarget deliveryTarget(UUID teacherId,UUID reportId) {
		return jdbc.sql("""
			SELECT report.id,report.ai_status,parent_student.parent_id,artifact.id artifact_id,artifact.sha256
			FROM monthly_reports report
			LEFT JOIN parent_student_relationships parent_student ON parent_student.student_id=report.student_id AND parent_student.status='ACTIVE'
			LEFT JOIN LATERAL (SELECT * FROM monthly_report_artifacts a WHERE a.report_id=report.id AND a.status='READY' ORDER BY revision_no DESC LIMIT 1) artifact ON true
			WHERE report.teacher_id=:teacherId AND report.id=:reportId
			""").param("teacherId",teacherId).param("reportId",reportId).query((rs,n)->new DeliveryTarget(
			rs.getObject("id",UUID.class),rs.getString("ai_status"),rs.getObject("parent_id",UUID.class),
			rs.getObject("artifact_id",UUID.class),rs.getString("sha256"))).optional().orElseThrow();
	}

	public void queueDelivery(UUID id,UUID teacherId,DeliveryTarget target,String key,String hash,Instant now) {
		jdbc.sql("""
			INSERT INTO monthly_report_deliveries(id,teacher_id,report_id,artifact_id,parent_id,client_idempotency_key,request_hash,queued_at)
			VALUES(:id,:teacherId,:reportId,:artifactId,:parentId,:key,:hash,:now) ON CONFLICT DO NOTHING
			""").param("id",id).param("teacherId",teacherId).param("reportId",target.reportId()).param("artifactId",target.artifactId())
			.param("parentId",target.parentId()).param("key",key).param("hash",hash).param("now",time(now)).update();
	}

	private static ReportRow row(ResultSet rs,int n)throws SQLException { return new ReportRow(rs.getObject("id",UUID.class),rs.getString("request_hash"),rs.getString("request_status")); }
	private static ReportView view(ResultSet rs,int n)throws SQLException { return new ReportView(rs.getObject("id",UUID.class),rs.getObject("student_id",UUID.class),
		rs.getString("student_name"),rs.getObject("class_group_id",UUID.class),rs.getString("class_name"),rs.getObject("report_month",LocalDate.class),
		rs.getString("report_kind"),rs.getString("request_status"),rs.getString("ai_status"),rs.getString("delivery_status"),
		rs.getString("recipient_status"),rs.getObject("block_count",Integer.class),rs.getObject("artifact_id",UUID.class),
		rs.getObject("page_count",Integer.class),rs.getString("artifact_hash"),rs.getString("ai_payload"),
		rs.getString("error_code"),rs.getObject("updated_at",OffsetDateTime.class).toInstant()); }
	private static OffsetDateTime time(Instant value){return value.atOffset(ZoneOffset.UTC);} private static Object nullable(Object value){return value==null?new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.OTHER,null):value;}

	public record StudentContext(UUID studentId,String studentName,UUID classId,String className,String guardianRef,UUID parentId){}
	public record Diagnosis(String payload,String snapshotHash){}
	public record ItemResult(UUID id,String areaTag,String typeTag,String skillNodeId,int chosenNo,int correctNo,boolean correct,String misconceptionTag,Instant occurredAt){}
	public record ReportInsert(UUID id,UUID teacherId,UUID studentId,UUID classId,String guardianRef,LocalDate month,String kind,String clientKey,String hash,String source,String tenantAlias,Instant now){}
	public record ReportRow(UUID id,String hash,String status){}
	public record ReportView(UUID reportId,UUID studentId,String studentDisplayName,UUID classId,String className,LocalDate period,String reportKind,String requestStatus,String aiStatus,String deliveryStatus,String recipientStatus,Integer blockCount,UUID artifactId,Integer pageCount,String artifactHash,String aiPayload,String errorCode,Instant updatedAt){}
	public record DeliveryTarget(UUID reportId,String aiStatus,UUID parentId,UUID artifactId,String artifactHash){}
	public record OutboxEvent(UUID id,UUID teacherId,UUID reportId,String tenantAlias,String payload,int attempts){}
}
