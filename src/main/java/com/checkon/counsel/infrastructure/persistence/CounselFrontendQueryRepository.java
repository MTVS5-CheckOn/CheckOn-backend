package com.checkon.counsel.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CounselFrontendQueryRepository {

	private final JdbcClient jdbc;

	public CounselFrontendQueryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long countGuardians(UUID teacherId) {
		return jdbc.sql("""
			SELECT count(DISTINCT relationship.parent_id)
			FROM parent_teacher_relationships relationship
			JOIN parent_student_relationships parent_student
			  ON parent_student.parent_id = relationship.parent_id
			 AND parent_student.status = 'ACTIVE'
			JOIN teacher_student_relationships teacher_student
			  ON teacher_student.teacher_id = relationship.teacher_id
			 AND teacher_student.student_id = parent_student.student_id
			 AND teacher_student.status = 'ACTIVE'
			WHERE relationship.teacher_id = :teacherId
			  AND relationship.status = 'ACTIVE'
			""").param("teacherId", teacherId).query(Long.class).single();
	}

	public List<GuardianRow> findGuardians(UUID teacherId, int page, int size) {
		return jdbc.sql("""
			WITH active_guardians AS (
			    SELECT DISTINCT relationship.parent_id
			    FROM parent_teacher_relationships relationship
			    JOIN parent_student_relationships parent_student
			      ON parent_student.parent_id = relationship.parent_id
			     AND parent_student.status = 'ACTIVE'
			    JOIN teacher_student_relationships teacher_student
			      ON teacher_student.teacher_id = relationship.teacher_id
			     AND teacher_student.student_id = parent_student.student_id
			     AND teacher_student.status = 'ACTIVE'
			    WHERE relationship.teacher_id = :teacherId
			      AND relationship.status = 'ACTIVE'
			), active_students AS (
			    SELECT parent_student.parent_id, parent_student.student_id
			    FROM parent_student_relationships parent_student
			    JOIN active_guardians guardian ON guardian.parent_id = parent_student.parent_id
			    JOIN teacher_student_relationships teacher_student
			      ON teacher_student.teacher_id = :teacherId
			     AND teacher_student.student_id = parent_student.student_id
			     AND teacher_student.status = 'ACTIVE'
			    WHERE parent_student.status = 'ACTIVE'
			), communication AS (
			    SELECT students.parent_id, inquiry.received_at AS occurred_at
			    FROM active_students students
			    JOIN counsel_inquiries inquiry
			      ON inquiry.teacher_id = :teacherId AND inquiry.student_id = students.student_id
			    UNION ALL
			    SELECT students.parent_id, job.sent_at AS occurred_at
			    FROM active_students students
			    JOIN counsel_inquiries inquiry
			      ON inquiry.teacher_id = :teacherId AND inquiry.student_id = students.student_id
			    JOIN counsel_draft_jobs job
			      ON job.teacher_id = inquiry.teacher_id AND job.inquiry_ref = inquiry.inquiry_ref
			    WHERE job.sent_text IS NOT NULL AND job.sent_at IS NOT NULL
			), latest_inquiries AS (
			    SELECT DISTINCT ON (students.parent_id)
			           students.parent_id, inquiry.topic, inquiry.urgency,
			           latest_job.job_id, latest_job.job_phase
			    FROM active_students students
			    JOIN counsel_inquiries inquiry
			      ON inquiry.teacher_id = :teacherId AND inquiry.student_id = students.student_id
			    LEFT JOIN LATERAL (
			        SELECT job.job_id, job.job_phase
			        FROM counsel_draft_jobs job
			        WHERE job.teacher_id = inquiry.teacher_id
			          AND job.inquiry_ref = inquiry.inquiry_ref
			        ORDER BY job.requested_at DESC, job.job_id DESC
			        LIMIT 1
			    ) latest_job ON true
			    ORDER BY students.parent_id, inquiry.received_at DESC, inquiry.inquiry_ref DESC
			), guardian_summaries AS (
			SELECT guardian.parent_id,
			       count(communication.occurred_at) AS communication_count,
			       max(communication.occurred_at) AS latest_communication_at,
			       latest.topic AS latest_topic,
			       latest.urgency AS latest_urgency,
			       latest.job_id AS latest_job_id,
			       latest.job_phase AS latest_job_phase
			FROM active_guardians guardian
			LEFT JOIN communication ON communication.parent_id = guardian.parent_id
			LEFT JOIN latest_inquiries latest ON latest.parent_id = guardian.parent_id
			GROUP BY guardian.parent_id, latest.topic, latest.urgency, latest.job_id, latest.job_phase
			)
			SELECT * FROM guardian_summaries
			ORDER BY latest_communication_at DESC NULLS LAST, parent_id
			LIMIT :size OFFSET :offset
			""").param("teacherId", teacherId).param("size", size).param("offset", (long) page * size)
			.query(CounselFrontendQueryRepository::mapGuardian).list();
	}

	public List<LinkedStudentRow> findLinkedStudents(UUID teacherId, UUID parentId) {
		return jdbc.sql("""
			SELECT student.id AS student_id, personal.real_name,
			       class_group.id AS class_id, class_group.name AS class_name
			FROM parent_student_relationships parent_student
			JOIN student_profiles student ON student.id = parent_student.student_id
			JOIN teacher_student_relationships teacher_student
			  ON teacher_student.teacher_id = :teacherId
			 AND teacher_student.student_id = student.id
			 AND teacher_student.status = 'ACTIVE'
			LEFT JOIN student_personal_information personal ON personal.student_id = student.id
			LEFT JOIN class_enrollments enrollment
			  ON enrollment.teacher_id = :teacherId
			 AND enrollment.student_id = student.id
			 AND enrollment.status = 'ACTIVE'
			LEFT JOIN class_groups class_group
			  ON class_group.id = enrollment.class_group_id
			 AND class_group.teacher_id = :teacherId
			 AND class_group.status = 'ACTIVE'
			WHERE parent_student.parent_id = :parentId
			  AND parent_student.status = 'ACTIVE'
			ORDER BY personal.real_name NULLS LAST, student.id
			""").param("teacherId", teacherId).param("parentId", parentId)
			.query((rs, row) -> new LinkedStudentRow(
				rs.getObject("student_id", UUID.class), rs.getString("real_name"),
				rs.getObject("class_id", UUID.class), rs.getString("class_name")
			)).list();
	}

	public List<CurrentLabelRow> findCurrentLabels(UUID teacherId, UUID parentId) {
		return jdbc.sql("""
			SELECT axis, value, updated_at
			FROM guardian_labels
			WHERE teacher_id = :teacherId AND parent_id = :parentId
			ORDER BY axis
			""").param("teacherId", teacherId).param("parentId", parentId)
			.query((rs, row) -> new CurrentLabelRow(
				rs.getString("axis"), rs.getString("value"), instant(rs, "updated_at")
			)).list();
	}

	public boolean hasActiveGuardian(UUID teacherId, UUID parentId) {
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM parent_teacher_relationships parent_teacher
			    JOIN parent_student_relationships parent_student
			      ON parent_student.parent_id = parent_teacher.parent_id
			     AND parent_student.status = 'ACTIVE'
			    JOIN teacher_student_relationships teacher_student
			      ON teacher_student.teacher_id = parent_teacher.teacher_id
			     AND teacher_student.student_id = parent_student.student_id
			     AND teacher_student.status = 'ACTIVE'
			    WHERE parent_teacher.teacher_id = :teacherId
			      AND parent_teacher.parent_id = :parentId
			      AND parent_teacher.status = 'ACTIVE'
			)
			""").param("teacherId", teacherId).param("parentId", parentId).query(Boolean.class).single();
	}

	public long countCommunications(UUID teacherId, UUID parentId) {
		return jdbc.sql(communicationCte() + "SELECT count(*) FROM communication")
			.param("teacherId", teacherId).param("parentId", parentId).query(Long.class).single();
	}

	public List<CommunicationRow> findCommunications(UUID teacherId, UUID parentId, int page, int size) {
		return jdbc.sql(communicationCte() + """
			SELECT record_id, direction, occurred_at, body, student_id, student_name,
			       inquiry_ref, topic, urgency, actually_sent, job_id, job_phase
			FROM communication
			ORDER BY occurred_at DESC, record_id DESC
			LIMIT :size OFFSET :offset
			""").param("teacherId", teacherId).param("parentId", parentId)
			.param("size", size).param("offset", (long) page * size)
			.query(CounselFrontendQueryRepository::mapCommunication).list();
	}

	public long countInquiries(UUID teacherId) {
		return jdbc.sql(inquiryCte() + "SELECT count(*) FROM available_inquiries")
			.param("teacherId", teacherId).query(Long.class).single();
	}

	public List<InquiryRow> findInquiries(UUID teacherId, int page, int size) {
		return jdbc.sql(inquiryCte() + """
			SELECT inquiry_ref, parent_id, student_id, student_name, class_id, class_name,
			       topic, urgency, received_at, raw_text, labels, period_label, facts,
			       job_id, job_phase
			FROM available_inquiries
			ORDER BY received_at DESC, inquiry_ref DESC
			LIMIT :size OFFSET :offset
			""").param("teacherId", teacherId).param("size", size).param("offset", (long) page * size)
			.query(CounselFrontendQueryRepository::mapInquiry).list();
	}

	private static String communicationCte() {
		return """
			WITH active_students AS (
			    SELECT parent_student.student_id
			    FROM parent_student_relationships parent_student
			    JOIN parent_teacher_relationships parent_teacher
			      ON parent_teacher.parent_id = parent_student.parent_id
			     AND parent_teacher.teacher_id = :teacherId
			     AND parent_teacher.status = 'ACTIVE'
			    JOIN teacher_student_relationships teacher_student
			      ON teacher_student.teacher_id = :teacherId
			     AND teacher_student.student_id = parent_student.student_id
			     AND teacher_student.status = 'ACTIVE'
			    WHERE parent_student.parent_id = :parentId AND parent_student.status = 'ACTIVE'
			), communication AS (
			    SELECT 'inquiry:' || inquiry.inquiry_ref AS record_id,
			           'inbound' AS direction, inquiry.received_at AS occurred_at,
			           inquiry.raw_text AS body, inquiry.student_id, personal.real_name AS student_name,
			           inquiry.inquiry_ref, inquiry.topic, inquiry.urgency, false AS actually_sent,
			           latest_job.job_id, latest_job.job_phase
			    FROM counsel_inquiries inquiry
			    JOIN active_students students ON students.student_id = inquiry.student_id
			    LEFT JOIN student_personal_information personal ON personal.student_id = inquiry.student_id
			    LEFT JOIN LATERAL (
			        SELECT job.job_id, job.job_phase
			        FROM counsel_draft_jobs job
			        WHERE job.teacher_id = inquiry.teacher_id AND job.inquiry_ref = inquiry.inquiry_ref
			        ORDER BY job.requested_at DESC, job.job_id DESC LIMIT 1
			    ) latest_job ON true
			    WHERE inquiry.teacher_id = :teacherId
			    UNION ALL
			    SELECT 'sent:' || job.job_id AS record_id,
			           'outbound' AS direction, job.sent_at AS occurred_at,
			           job.sent_text AS body, inquiry.student_id, personal.real_name AS student_name,
			           inquiry.inquiry_ref, inquiry.topic, inquiry.urgency, true AS actually_sent,
			           job.job_id, job.job_phase
			    FROM counsel_draft_jobs job
			    JOIN counsel_inquiries inquiry
			      ON inquiry.teacher_id = job.teacher_id AND inquiry.inquiry_ref = job.inquiry_ref
			    JOIN active_students students ON students.student_id = inquiry.student_id
			    LEFT JOIN student_personal_information personal ON personal.student_id = inquiry.student_id
			    WHERE job.teacher_id = :teacherId AND job.sent_text IS NOT NULL AND job.sent_at IS NOT NULL
			)
			""";
	}

	private static String inquiryCte() {
		return """
			WITH available_inquiries AS (
			    SELECT inquiry.inquiry_ref, parent_student.parent_id, inquiry.student_id,
			           personal.real_name AS student_name, inquiry.class_id, class_group.name AS class_name,
			           inquiry.topic, inquiry.urgency, inquiry.received_at, inquiry.raw_text,
			           inquiry.labels::text AS labels, inquiry.period_label,
			           inquiry.facts::text AS facts, latest_job.job_id, latest_job.job_phase
			    FROM counsel_inquiries inquiry
			    JOIN teacher_student_relationships teacher_student
			      ON teacher_student.teacher_id = :teacherId
			     AND teacher_student.student_id = inquiry.student_id
			     AND teacher_student.status = 'ACTIVE'
			    JOIN parent_student_relationships parent_student
			      ON parent_student.student_id = inquiry.student_id
			     AND parent_student.status = 'ACTIVE'
			    JOIN parent_teacher_relationships parent_teacher
			      ON parent_teacher.parent_id = parent_student.parent_id
			     AND parent_teacher.teacher_id = :teacherId
			     AND parent_teacher.status = 'ACTIVE'
			    JOIN class_groups class_group
			      ON class_group.id = inquiry.class_id
			     AND class_group.teacher_id = :teacherId
			     AND class_group.status = 'ACTIVE'
			    JOIN class_enrollments enrollment
			      ON enrollment.class_group_id = inquiry.class_id
			     AND enrollment.teacher_id = :teacherId
			     AND enrollment.student_id = inquiry.student_id
			     AND enrollment.status = 'ACTIVE'
			    LEFT JOIN student_personal_information personal ON personal.student_id = inquiry.student_id
			    LEFT JOIN LATERAL (
			        SELECT job.job_id, job.job_phase
			        FROM counsel_draft_jobs job
			        WHERE job.teacher_id = inquiry.teacher_id AND job.inquiry_ref = inquiry.inquiry_ref
			        ORDER BY job.requested_at DESC, job.job_id DESC LIMIT 1
			    ) latest_job ON true
			    WHERE inquiry.teacher_id = :teacherId
			)
			""";
	}

	private static GuardianRow mapGuardian(ResultSet rs, int row) throws SQLException {
		return new GuardianRow(
			rs.getObject("parent_id", UUID.class), rs.getLong("communication_count"),
			nullableInstant(rs, "latest_communication_at"), rs.getString("latest_topic"),
			rs.getString("latest_urgency"), rs.getString("latest_job_id"), rs.getString("latest_job_phase")
		);
	}

	private static CommunicationRow mapCommunication(ResultSet rs, int row) throws SQLException {
		return new CommunicationRow(
			rs.getString("record_id"), rs.getString("direction"), instant(rs, "occurred_at"),
			rs.getString("body"), rs.getObject("student_id", UUID.class), rs.getString("student_name"),
			rs.getString("inquiry_ref"), rs.getString("topic"), rs.getString("urgency"),
			rs.getBoolean("actually_sent"), rs.getString("job_id"), rs.getString("job_phase")
		);
	}

	private static InquiryRow mapInquiry(ResultSet rs, int row) throws SQLException {
		return new InquiryRow(
			rs.getString("inquiry_ref"), rs.getObject("parent_id", UUID.class),
			rs.getObject("student_id", UUID.class), rs.getString("student_name"),
			rs.getObject("class_id", UUID.class), rs.getString("class_name"),
			rs.getString("topic"), rs.getString("urgency"), instant(rs, "received_at"),
			rs.getString("raw_text"), rs.getString("labels"), rs.getString("period_label"),
			rs.getString("facts"), rs.getString("job_id"), rs.getString("job_phase")
		);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}

	private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	public record GuardianRow(
		UUID parentId, long communicationCount, Instant latestCommunicationAt,
		String latestTopic, String latestUrgency, String latestJobId, String latestJobPhase
	) { }

	public record LinkedStudentRow(UUID studentId, String studentName, UUID classId, String className) { }

	public record CurrentLabelRow(String axis, String value, Instant updatedAt) { }

	public record CommunicationRow(
		String recordId, String direction, Instant occurredAt, String body,
		UUID studentId, String studentName, String inquiryRef, String topic, String urgency,
		boolean actuallySent, String jobId, String jobPhase
	) { }

	public record InquiryRow(
		String inquiryRef, UUID parentId, UUID studentId, String studentName,
		UUID classId, String className, String topic, String urgency, Instant receivedAt,
		String rawText, String labelsJson, String periodLabel, String factsJson,
		String jobId, String jobPhase
	) { }
}
