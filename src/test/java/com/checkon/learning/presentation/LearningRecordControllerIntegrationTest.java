package com.checkon.learning.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.learning.application.LearningRecordSnapshotService;
import com.checkon.support.RosterTestFixture;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LearningRecordControllerIntegrationTest {
	@Container @ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");
	private static final UUID TEACHER = UUID.fromString("0198c000-0000-7000-8000-000000000001");
	private static final UUID STUDENT = UUID.fromString("0198c000-0000-7000-8000-000000000002");
	private static final UUID CLASS_GROUP = UUID.fromString("0198c000-0000-7000-8000-000000000003");
	private static final UUID OTHER_TEACHER = UUID.fromString("0198c000-0000-7000-8000-000000000011");
	private static final UUID OTHER_STUDENT = UUID.fromString("0198c000-0000-7000-8000-000000000012");
	private static final UUID OTHER_CLASS = UUID.fromString("0198c000-0000-7000-8000-000000000013");
	private static final UUID INACTIVE_STUDENT = UUID.fromString("0198c000-0000-7000-8000-000000000022");
	private static final Instant OCCURRED_AT = Instant.parse("2026-08-03T01:00:00Z");

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired LearningRecordSnapshotService snapshotService;

	@BeforeEach
	void fixtures() {
		jdbc.update("DELETE FROM detection_result_evidence");
		jdbc.update("DELETE FROM detection_signal_results");
		jdbc.update("DELETE FROM detection_request_attempts");
		jdbc.update("DELETE FROM detection_runs");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM learning_records");
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		insertRoster(TEACHER, STUDENT, CLASS_GROUP, "ACTIVE");
		insertRoster(OTHER_TEACHER, OTHER_STUDENT, OTHER_CLASS, "ACTIVE");
		insertStudentRelationship(TEACHER, INACTIVE_STUDENT, "ENDED");
		insertEnrollment(TEACHER, STUDENT, CLASS_GROUP,
			OCCURRED_AT.minusSeconds(60), null);
	}

	@Test
	void authenticatedTeacherRegistersManualRecordAndDetectionCanReadIt() throws Exception {
		String response = mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, CLASS_GROUP, null)))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
				"/api/v1/learning-records/[0-9a-f-]+")))
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(response.substring(response.indexOf(':') + 2, response.length() - 2));
		assertThat(jdbc.queryForObject(
			"SELECT teacher_id FROM learning_records WHERE id = ?", UUID.class, id
		)).isEqualTo(TEACHER);
		assertThat(jdbc.queryForObject(
			"SELECT source_type FROM learning_records WHERE id = ?", String.class, id
		)).isEqualTo("MANUAL");
		var snapshot = snapshotService.build(TEACHER, LocalDate.of(2026, 8, 3), "normal",
			OCCURRED_AT.minusSeconds(1), OCCURRED_AT.plusSeconds(1));
		assertThat(snapshot.learningEvents()).singleElement().satisfies(event ->
			assertThat(event.recordId()).contains(id.toString().replace("-", "")));
	}

	@Test
	void ignoresBodyTeacherIdAndAllowsNullAndDuplicateExternalReference() throws Exception {
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, null).replace("{", "{\"teacherId\":\"" + OTHER_TEACHER + "\",")))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, "same-ref")))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, "same-ref")))
			.andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_records", Integer.class))
			.isEqualTo(3);
	}

	@Test
	void rejectsMalformedAndInvalidValues() throws Exception {
		for (String body : List.of(
			"{}",
			validRequest(STUDENT, null, null).replace("SOLVE", "UNKNOWN"),
			validRequest(STUDENT, null, null).replace("\"durationSec\": 120", "\"durationSec\": -1"),
			validRequest(STUDENT, null, null).replace("\"passageWordCount\": 800", "\"passageWordCount\": -1"),
			"{not-json"
		)) {
			mockMvc.perform(post("/api/v1/learning-records")
					.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	@Test
	@DisplayName("Given AI 계약의 태그 조합, When 학습기록을 등록하면, Then 문항 단위 태그를 그대로 저장한다")
	void givenContractTagCombinations_whenRegisteringRecords_thenStoresTagsExactly()
		throws Exception {
		// Given
		List<String[]> combinations = List.of(
			new String[]{"reading", "common", "fact", "mcq"},
			new String[]{"literature", "common", "infer", "mcq"},
			new String[]{"speech_writing", "elective", "critic", "mcq"},
			new String[]{"language", "elective", "concept", "mcq"},
			new String[]{"media", "elective", "apply", "mcq"}
		);

		// When
		for (String[] tags : combinations) {
			mockMvc.perform(post("/api/v1/learning-records")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content(taggedRequest(tags[0], tags[1], tags[2], tags[3])))
				.andExpect(status().isCreated());
		}

		// Then
		assertThat(jdbc.queryForList("""
			SELECT area_tag, subject_track, type_tag, item_format
			FROM learning_records
			ORDER BY area_tag
			""")).extracting(
			row -> row.get("area_tag") + ":" + row.get("subject_track") + ":"
				+ row.get("type_tag") + ":" + row.get("item_format")
		).containsExactlyInAnyOrder(
			"reading:common:fact:mcq",
			"literature:common:infer:mcq",
			"speech_writing:elective:critic:mcq",
			"language:elective:concept:mcq",
			"media:elective:apply:mcq"
		);
		var snapshot = snapshotService.build(
			TEACHER, LocalDate.of(2026, 8, 3), "normal",
			OCCURRED_AT.minusSeconds(1), OCCURRED_AT.plusSeconds(1)
		);
		assertThat(snapshot.learningEvents()).extracting(event ->
			event.areaTag() + ":" + event.subjectTrack() + ":"
				+ event.typeTag() + ":" + event.itemFormat()
		).containsExactlyInAnyOrder(
			"reading:common:fact:mcq",
			"literature:common:infer:mcq",
			"speech_writing:elective:critic:mcq",
			"language:elective:concept:mcq",
			"media:elective:apply:mcq"
		);
	}

	@Test
	@DisplayName("Given null인 위험신호 태그, When 학습기록을 등록하면, Then 값을 추론하지 않고 null로 저장한다")
	void givenNullDetectionTags_whenRegisteringRecord_thenKeepsTagsNull() throws Exception {
		// Given
		String request = taggedRequest(null, null, null, null);

		// When
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isCreated());

		// Then
		assertThat(jdbc.queryForMap("""
			SELECT area_tag, subject_track, type_tag, item_format
			FROM learning_records
			""")).allSatisfy((column, value) -> assertThat(value)
			.as(column + " must remain null").isNull());
	}

	@Test
	@DisplayName("Given 구버전·미지원·불일치 태그, When 학습기록을 등록하면, Then 해당 행만 400으로 거절한다")
	void givenInvalidDetectionTags_whenRegisteringRecord_thenRejectsOnlyThatRecord()
		throws Exception {
		// Given
		List<String> invalidRequests = List.of(
			taggedRequest("speech", "elective", "fact", "mcq"),
			taggedRequest("writing", "elective", "fact", "mcq"),
			taggedRequest("Literature", "common", "infer", "mcq"),
			taggedRequest("reading", "elective", "fact", "mcq"),
			taggedRequest("language", "common", "concept", "mcq"),
			taggedRequest("reading", "common", "analysis", "mcq"),
			taggedRequest("reading", "common", "fact", "short"),
			taggedRequest("reading", "common", "fact", "essay")
		);

		// When/Then
		for (String request : invalidRequests) {
			mockMvc.perform(post("/api/v1/learning-records")
					.with(teacherAuthentication(TEACHER))
					.contentType(MediaType.APPLICATION_JSON)
					.content(request))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM learning_records", Integer.class
		)).isZero();
	}

	@Test
	void concealsOtherTenantAndInactiveTargets() throws Exception {
		for (String body : List.of(
			validRequest(OTHER_STUDENT, null, null),
			validRequest(INACTIVE_STUDENT, null, null),
			validRequest(STUDENT, OTHER_CLASS, null)
		)) {
			mockMvc.perform(post("/api/v1/learning-records")
					.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LEARNING_RECORD_TARGET_NOT_FOUND"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_records", Integer.class)).isZero();
	}

	@Test
	void validatesClassEnrollmentAtTheRecordOccurrenceTime() throws Exception {
		jdbc.update("DELETE FROM class_enrollments WHERE teacher_id = ?", TEACHER);

		// 입반 시각은 포함한다.
		insertEnrollment(TEACHER, STUDENT, CLASS_GROUP, OCCURRED_AT, null);
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, CLASS_GROUP, null)))
			.andExpect(status().isCreated());

		jdbc.update("DELETE FROM learning_records");
		jdbc.update("DELETE FROM class_enrollments WHERE teacher_id = ?", TEACHER);
		insertEnrollment(TEACHER, STUDENT, CLASS_GROUP,
			OCCURRED_AT.minusSeconds(60), OCCURRED_AT.plusSeconds(1));
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, CLASS_GROUP, null)))
			.andExpect(status().isCreated());
	}

	@Test
	void rejectsClassEnrollmentOutsideTheRecordOccurrenceTime() throws Exception {
		for (Instant[] interval : List.of(
			new Instant[]{OCCURRED_AT.plusSeconds(1), null},
			new Instant[]{OCCURRED_AT.minusSeconds(60), OCCURRED_AT}
		)) {
			jdbc.update("DELETE FROM class_enrollments WHERE teacher_id = ?", TEACHER);
			insertEnrollment(TEACHER, STUDENT, CLASS_GROUP, interval[0], interval[1]);
			mockMvc.perform(post("/api/v1/learning-records")
					.with(teacherAuthentication(TEACHER)).contentType(MediaType.APPLICATION_JSON)
					.content(validRequest(STUDENT, CLASS_GROUP, null)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LEARNING_RECORD_TARGET_NOT_FOUND"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_records", Integer.class))
			.isZero();
	}

	@Test
	void requiresAuthenticationAndValidTeacherProfile() throws Exception {
		mockMvc.perform(post("/api/v1/learning-records")
				.contentType(MediaType.APPLICATION_JSON).content(validRequest(STUDENT, null, null)))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/learning-records")
				.with(teacherAuthentication(null)).contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, null)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TEACHER_PRINCIPAL"));
		mockMvc.perform(post("/api/v1/learning-records")
				.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
					new AuthenticatedAccount(UUID.randomUUID(), AccountRole.PARENT, null,
						UUID.randomUUID()),
					null,
					List.of(new SimpleGrantedAuthority("ROLE_PARENT"))
				)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(STUDENT, null, null)))
			.andExpect(status().isForbidden());
	}

	private String validRequest(UUID studentId, UUID classGroupId, String externalRef) {
		return """
			{
			  "studentId": "%s",
			  %s
			  "recordType": "SOLVE",
			  "occurredAt": "%s",
			  %s
			  "correct": true,
			  "durationSec": 120,
			  "passageWordCount": 800,
			  "areaTag": "reading"
			}
			""".formatted(studentId,
			classGroupId == null ? "" : "\"classGroupId\": \"" + classGroupId + "\",",
			OCCURRED_AT,
			externalRef == null ? "" : "\"externalRecordRef\": \"" + externalRef + "\",");
	}

	private String taggedRequest(
		String areaTag,
		String subjectTrack,
		String typeTag,
		String itemFormat
	) {
		return """
			{
			  "studentId": "%s",
			  "classGroupId": "%s",
			  "recordType": "SOLVE",
			  "occurredAt": "%s",
			  "correct": false,
			  "durationSec": 180,
			  "passageWordCount": 800,
			  "areaTag": %s,
			  "subjectTrack": %s,
			  "typeTag": %s,
			  "itemFormat": %s
			}
			""".formatted(
			STUDENT, CLASS_GROUP, OCCURRED_AT,
			jsonValue(areaTag), jsonValue(subjectTrack), jsonValue(typeTag), jsonValue(itemFormat)
		);
	}

	private String jsonValue(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

	private void insertRoster(UUID teacherId, UUID studentId, UUID classId, String status) {
		RosterTestFixture.insertTeacher(jdbc, teacherId);
		insertStudentRelationship(teacherId, studentId, status);
		Instant now = Instant.parse("2026-08-01T00:00:00Z");
		jdbc.update("""
			INSERT INTO class_groups (id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, '테스트 반', '수학', 'ACTIVE', ?, ?)
			""", classId, teacherId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
	}

	private void insertStudentRelationship(UUID teacherId, UUID studentId, String status) {
		Instant now = Instant.parse("2026-08-01T00:00:00Z");
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?)
			""", studentId, "학생-" + studentId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, ended_at, created_at)
			VALUES (uuidv7(), ?, ?, ?, ?, ?, ?)
			""", teacherId, studentId, status, now.atOffset(ZoneOffset.UTC),
			"ENDED".equals(status) ? now.plusSeconds(1).atOffset(ZoneOffset.UTC) : null,
			now.atOffset(ZoneOffset.UTC));
	}

	private void insertEnrollment(
		UUID teacherId,
		UUID studentId,
		UUID classGroupId,
		Instant enrolledAt,
		Instant endedAt
	) {
		String status = endedAt == null ? "ACTIVE" : "ENDED";
		jdbc.update("""
			INSERT INTO class_enrollments
			(id, class_group_id, teacher_id, student_id, status,
			 enrolled_at, ended_at, created_at)
			VALUES (uuidv7(), ?, ?, ?, ?, ?, ?, ?)
			""", classGroupId, teacherId, studentId, status,
			enrolledAt.atOffset(ZoneOffset.UTC),
			endedAt == null ? null : endedAt.atOffset(ZoneOffset.UTC),
			enrolledAt.atOffset(ZoneOffset.UTC));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(
		UUID teacherId
	) {
		AuthenticatedAccount principal = new AuthenticatedAccount(
			UUID.randomUUID(), AccountRole.TEACHER, teacherId, UUID.randomUUID());
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			principal, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
	}
}
