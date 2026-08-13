package com.checkon.roster.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.checkon.roster.application.ClassManagementService;
import com.checkon.support.RosterTestFixture;

@SpringBootTest(properties = "checkon.security.test-authentication.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import(ClassGroupControllerIntegrationTest.SteppingClockConfiguration.class)
class ClassGroupControllerIntegrationTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER =
		UUID.fromString("0198f300-0000-7000-8000-000000000001");
	private static final UUID OTHER_TEACHER =
		UUID.fromString("0198f300-0000-7000-8000-000000000002");
	private static final UUID CLASS_LOW =
		UUID.fromString("0198f300-0000-7000-8000-000000000010");
	private static final UUID CLASS_HIGH =
		UUID.fromString("0198f300-0000-7000-8000-000000000020");
	private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
	private static final Instant CREATED_AT = NOW.minusSeconds(3600);

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource dataSource;
	@Autowired ClassManagementService service;
	@Autowired SteppingClock clock;

	@TestConfiguration(proxyBeanMethods = false)
	static class SteppingClockConfiguration {
		@Bean
		@Primary
		SteppingClock classManagementTestClock() {
			return new SteppingClock(NOW);
		}
	}

	static final class SteppingClock extends Clock {
		private final Instant base;
		private final AtomicLong calls = new AtomicLong();

		SteppingClock(Instant base) {
			this.base = base;
		}

		void reset() {
			calls.set(0);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) {
				throw new IllegalArgumentException("test clock only supports UTC");
			}
			return this;
		}

		@Override
		public Instant instant() {
			return base.plusSeconds(calls.getAndIncrement());
		}
	}

	@BeforeEach
	void setUp() {
		clock.reset();
		jdbc.update("DELETE FROM class_enrollments");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM class_groups");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
	}

	@Test
	void teacherCanCreateListDetailAndUpdateAClass() throws Exception {
		mvc.perform(post("/api/v1/classes")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"  수능 국어 대비 반  ","subject":"  국어  ","memo":null,
					 "teacherId":"0198f300-0000-7000-8000-000000000002"}
					"""))
			.andExpect(status().isCreated())
			.andExpect(header().exists("Location"))
			.andExpect(jsonPath("$.name").value("수능 국어 대비 반"))
			.andExpect(jsonPath("$.subject").value("국어"))
			.andExpect(jsonPath("$.memo").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.activeStudentCount").value(0));

		UUID classId = jdbc.queryForObject(
			"SELECT id FROM class_groups WHERE teacher_id = ?",
			UUID.class,
			TEACHER
		);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM class_groups WHERE teacher_id = ?",
			Integer.class,
			OTHER_TEACHER
		)).isZero();
		mvc.perform(get("/api/v1/classes")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.pageNumber").value(0))
			.andExpect(jsonPath("$.metadata.pageSize").value(20))
			.andExpect(jsonPath("$.metadata.itemCount").value(1))
			.andExpect(jsonPath("$.metadata.totalItemCount").value(1))
			.andExpect(jsonPath("$.metadata.totalPageCount").value(1))
			.andExpect(jsonPath("$.metadata.isFirst").value(true))
			.andExpect(jsonPath("$.metadata.isLast").value(true))
			.andExpect(jsonPath("$.items[0].classId").value(classId.toString()))
			.andExpect(jsonPath("$.items[0].rowNo").doesNotExist());

		mvc.perform(patch("/api/v1/classes/{classId}", classId)
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"심화 국어 반","subject":"문학","memo":"주 2회"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("심화 국어 반"))
			.andExpect(jsonPath("$.subject").value("문학"))
			.andExpect(jsonPath("$.memo").value("주 2회"));

		mvc.perform(get("/api/v1/classes/{classId}", classId)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.classId").value(classId.toString()))
			.andExpect(jsonPath("$.name").value("심화 국어 반"));
	}

	@Test
	void listCountsOnlyActiveEnrollmentsAndUsesStablePaging() throws Exception {
		insertClass(CLASS_LOW, TEACHER, "낮은 ID", "국어", null, "ACTIVE", CREATED_AT);
		insertClass(CLASS_HIGH, TEACHER, "높은 ID", "수학", "메모", "ACTIVE", CREATED_AT);
		UUID archivedClass = UUID.fromString("0198f300-0000-7000-8000-000000000030");
		insertClass(
			archivedClass, TEACHER, "보관 반", "영어", null, "ARCHIVED", CREATED_AT
		);
		insertStudentWithEnrollment(
			UUID.fromString("0198f300-0000-7000-8000-000000000101"),
			CLASS_HIGH,
			"ACTIVE",
			CREATED_AT,
			null
		);
		insertStudentWithEnrollment(
			UUID.fromString("0198f300-0000-7000-8000-000000000102"),
			CLASS_HIGH,
			"ENDED",
			CREATED_AT,
			CREATED_AT.plusSeconds(60)
		);

		mvc.perform(get("/api/v1/classes?page=0&size=1")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].classId").value(CLASS_HIGH.toString()))
			.andExpect(jsonPath("$.items[0].activeStudentCount").value(1))
			.andExpect(jsonPath("$.metadata.itemCount").value(1))
			.andExpect(jsonPath("$.metadata.totalItemCount").value(2))
			.andExpect(jsonPath("$.metadata.totalPageCount").value(2))
			.andExpect(jsonPath("$.metadata.isFirst").value(true))
			.andExpect(jsonPath("$.metadata.isLast").value(false));

		mvc.perform(get("/api/v1/classes?page=1&size=1")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].classId").value(CLASS_LOW.toString()))
			.andExpect(jsonPath("$.metadata.isFirst").value(false))
			.andExpect(jsonPath("$.metadata.isLast").value(true));

		mvc.perform(get("/api/v1/classes?page=2&size=1")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.metadata.itemCount").value(0))
			.andExpect(jsonPath("$.metadata.totalItemCount").value(2))
			.andExpect(jsonPath("$.metadata.isLast").value(true));
	}

	@Test
	void anotherTenantAndMissingClassReturnTheSameNotFound() throws Exception {
		UUID otherClass = UUID.randomUUID();
		insertClass(
			otherClass, OTHER_TEACHER, "다른 반", "수학", null, "ACTIVE", CREATED_AT
		);

		for (UUID classId : List.of(otherClass, UUID.randomUUID())) {
			mvc.perform(get("/api/v1/classes/{classId}", classId)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLASS_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("클래스를 찾을 수 없습니다."));
		}
	}

	@Test
	void unauthenticatedAndNonTeacherRequestsAreRejected() throws Exception {
		mvc.perform(get("/api/v1/classes"))
			.andExpect(status().isUnauthorized());

		for (AccountRole role : List.of(AccountRole.PARENT, AccountRole.STUDENT)) {
			mvc.perform(get("/api/v1/classes").with(roleAuthentication(role)))
				.andExpect(status().isForbidden());
		}

		mvc.perform(get("/api/v1/classes").with(authentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new AuthenticatedAccount(
					UUID.randomUUID(), AccountRole.TEACHER, null, UUID.randomUUID()
				),
				null,
				List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
			)
		)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TEACHER_PRINCIPAL"));
	}

	@Test
	void invalidFieldsAndPageBoundsAreRejected() throws Exception {
		mvc.perform(get("/api/v1/classes?size=100")
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.pageSize").value(100));

		for (String query : List.of("?page=-1", "?size=0", "?size=101")) {
			mvc.perform(get("/api/v1/classes" + query)
					.with(teacherAuthentication(TEACHER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		mvc.perform(post("/api/v1/classes")
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"반\",\"subject\":\"   \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void archiveEndsActiveEnrollmentsAtOneInstantAndPreservesHistory() throws Exception {
		insertClass(CLASS_LOW, TEACHER, "보관 대상", "국어", null, "ACTIVE", CREATED_AT);
		UUID activeStudent = UUID.randomUUID();
		UUID secondActiveStudent = UUID.randomUUID();
		UUID endedStudent = UUID.randomUUID();
		insertStudentWithEnrollment(activeStudent, CLASS_LOW, "ACTIVE", CREATED_AT, null);
		insertStudentWithEnrollment(
			secondActiveStudent, CLASS_LOW, "ACTIVE", CREATED_AT, null
		);
		Instant previousEnd = CREATED_AT.plusSeconds(120);
		insertStudentWithEnrollment(
			endedStudent, CLASS_LOW, "ENDED", CREATED_AT, previousEnd
		);

		mvc.perform(post("/api/v1/classes/{classId}/archive", CLASS_LOW)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"))
			.andExpect(jsonPath("$.activeStudentCount").value(0))
			.andExpect(jsonPath("$.updatedAt").value(NOW.toString()));

		assertThat(jdbc.query("""
			SELECT ended_at
			FROM class_enrollments
			WHERE class_group_id = ? AND student_id IN (?, ?)
			ORDER BY student_id
			""", (resultSet, rowNumber) -> resultSet.getObject(
				"ended_at", OffsetDateTime.class
			).toInstant(), CLASS_LOW, activeStudent, secondActiveStudent))
			.containsExactly(NOW, NOW);
		Instant endedAt = jdbc.queryForObject(
			"SELECT ended_at FROM class_enrollments WHERE student_id = ?",
			(resultSet, rowNumber) -> resultSet.getObject(
				"ended_at", OffsetDateTime.class
			).toInstant(),
			endedStudent
		);
		assertThat(endedAt).isEqualTo(previousEnd);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM class_enrollments WHERE class_group_id = ?",
			Integer.class,
			CLASS_LOW
		)).isEqualTo(3);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM student_profiles",
			Integer.class
		)).isEqualTo(3);

		mvc.perform(post("/api/v1/classes/{classId}/archive", CLASS_LOW)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.updatedAt").value(NOW.toString()));
		mvc.perform(get("/api/v1/classes").with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.metadata.totalPageCount").value(0))
			.andExpect(jsonPath("$.metadata.isFirst").value(true))
			.andExpect(jsonPath("$.metadata.isLast").value(true));
		mvc.perform(get("/api/v1/classes/{classId}", CLASS_LOW)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"));
		mvc.perform(patch("/api/v1/classes/{classId}", CLASS_LOW)
				.with(teacherAuthentication(TEACHER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"수정\",\"subject\":\"수학\",\"memo\":null}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_CLASS_STATE"));
	}

	@Test
	void archiveRollsBackFlushedEnrollmentChangesWhenClassTransitionFails() throws Exception {
		insertClass(
			CLASS_LOW,
			TEACHER,
			"미래 생성 반",
			"국어",
			null,
			"ACTIVE",
			NOW.plusSeconds(3600)
		);
		UUID studentId = UUID.randomUUID();
		insertStudentWithEnrollment(studentId, CLASS_LOW, "ACTIVE", CREATED_AT, null);

		mvc.perform(post("/api/v1/classes/{classId}/archive", CLASS_LOW)
				.with(teacherAuthentication(TEACHER)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_CLASS_STATE"));

		assertThat(jdbc.queryForObject(
			"SELECT status FROM class_groups WHERE id = ?",
			String.class,
			CLASS_LOW
		)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject(
			"SELECT status FROM class_enrollments WHERE student_id = ?",
			String.class,
			studentId
		)).isEqualTo("ACTIVE");
	}

	@Test
	void concurrentArchiveRequestsPreserveTheFirstTransitionTime() throws Exception {
		insertClass(CLASS_LOW, TEACHER, "동시 보관 반", "국어", null, "ACTIVE", CREATED_AT);
		insertStudentWithEnrollment(UUID.randomUUID(), CLASS_LOW, "ACTIVE", CREATED_AT, null);
		AuthenticatedAccount principal = teacherPrincipal(TEACHER);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> {
				ready.countDown();
				start.await();
				return service.archive(principal, CLASS_LOW);
			});
			var second = executor.submit(() -> {
				ready.countDown();
				start.await();
				return service.archive(principal, CLASS_LOW);
			});
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertThat(first.get(10, TimeUnit.SECONDS).updatedAt()).isEqualTo(NOW);
			assertThat(second.get(10, TimeUnit.SECONDS).updatedAt()).isEqualTo(NOW);
		}
		finally {
			executor.shutdownNow();
		}

		Instant updatedAt = jdbc.queryForObject(
			"SELECT updated_at FROM class_groups WHERE id = ?",
			(resultSet, rowNumber) -> resultSet.getObject(
				"updated_at", OffsetDateTime.class
			).toInstant(),
			CLASS_LOW
		);
		assertThat(updatedAt).isEqualTo(NOW);
	}

	@Test
	void databaseSerializesDirectEnrollmentAgainstDirectArchive() throws Exception {
		insertClass(CLASS_LOW, TEACHER, "입반 경쟁 반", "국어", null, "ACTIVE", CREATED_AT);
		UUID studentId = UUID.randomUUID();
		insertStudentRelationship(studentId);
		try (var enrollmentConnection = dataSource.getConnection()) {
			enrollmentConnection.setAutoCommit(false);
			try (var statement = enrollmentConnection.prepareStatement("""
				INSERT INTO class_enrollments (
				    class_group_id, teacher_id, student_id, status,
				    enrolled_at, created_at
				) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
				""")) {
				statement.setObject(1, CLASS_LOW);
				statement.setObject(2, TEACHER);
				statement.setObject(3, studentId);
				statement.setObject(4, CREATED_AT.atOffset(ZoneOffset.UTC));
				statement.setObject(5, CREATED_AT.atOffset(ZoneOffset.UTC));
				assertThat(statement.executeUpdate()).isOne();
			}

			try (var archiveConnection = dataSource.getConnection()) {
				archiveConnection.setAutoCommit(false);
				try {
					try (var lockTimeout = archiveConnection.createStatement()) {
						lockTimeout.execute("SET LOCAL lock_timeout = '500ms'");
					}
					assertThatThrownBy(() -> {
						try (var statement = archiveConnection.prepareStatement(
							"UPDATE class_groups SET status = 'ARCHIVED' WHERE id = ?"
						)) {
							statement.setObject(1, CLASS_LOW);
							statement.executeUpdate();
						}
					})
						.isInstanceOf(SQLException.class)
						.hasMessageContaining("lock timeout");
				}
				finally {
					archiveConnection.rollback();
				}
			}

			enrollmentConnection.commit();
		}

		assertThatThrownBy(() -> jdbc.update(
			"UPDATE class_groups SET status = 'ARCHIVED' WHERE id = ?",
			CLASS_LOW
		))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasRootCauseInstanceOf(SQLException.class)
			.hasStackTraceContaining("active enrollments must end before class archive");

		assertThat(jdbc.queryForObject(
			"SELECT status FROM class_groups WHERE id = ?",
			String.class,
			CLASS_LOW
		)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject(
			"SELECT status FROM class_enrollments WHERE student_id = ?",
			String.class,
			studentId
		)).isEqualTo("ACTIVE");
	}

	private void insertClass(
		UUID classId,
		UUID teacherId,
		String name,
		String subject,
		String memo,
		String status,
		Instant createdAt
	) {
		jdbc.update("""
			INSERT INTO class_groups (
			    id, teacher_id, name, subject, memo, status, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			""", classId, teacherId, name, subject, memo, status,
			createdAt.atOffset(ZoneOffset.UTC), createdAt.atOffset(ZoneOffset.UTC));
	}

	private void insertStudentWithEnrollment(
		UUID studentId,
		UUID classId,
		String status,
		Instant enrolledAt,
		Instant endedAt
	) {
		insertStudentRelationship(studentId);
		jdbc.update("""
			INSERT INTO class_enrollments (
			    class_group_id, teacher_id, student_id, status,
			    enrolled_at, ended_at, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?)
			""", classId, TEACHER, studentId, status,
			enrolledAt.atOffset(ZoneOffset.UTC),
			endedAt == null ? null : endedAt.atOffset(ZoneOffset.UTC),
			CREATED_AT.atOffset(ZoneOffset.UTC));
	}

	private void insertStudentRelationship(UUID studentId) {
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, created_at, updated_at)
			VALUES (?, ?, ?, ?)
			""", studentId, "학생-" + studentId,
			CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO teacher_student_relationships (
			    teacher_id, student_id, status, started_at, created_at
			) VALUES (?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, studentId,
			CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor teacherAuthentication(
		UUID teacherId
	) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			teacherPrincipal(teacherId),
			null,
			List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor roleAuthentication(
		AccountRole role
	) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			new AuthenticatedAccount(UUID.randomUUID(), role, null, UUID.randomUUID()),
			null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
		));
	}

	private AuthenticatedAccount teacherPrincipal(UUID teacherId) {
		UUID accountId = UUID.nameUUIDFromBytes(
			("account:" + teacherId).getBytes(StandardCharsets.UTF_8)
		);
		return new AuthenticatedAccount(
			accountId, AccountRole.TEACHER, teacherId, UUID.randomUUID()
		);
	}
}
