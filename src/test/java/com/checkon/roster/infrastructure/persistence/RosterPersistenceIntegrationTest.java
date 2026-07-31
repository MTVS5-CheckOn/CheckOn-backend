package com.checkon.roster.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.roster.domain.ClassEnrollment;
import com.checkon.roster.domain.ClassGroup;
import com.checkon.roster.domain.StudentProfile;
import com.checkon.roster.domain.TeacherStudentRelationship;

/**
 * 실제 PostgreSQL에서 Roster FK, CHECK, partial unique index와 UUIDv7을 검증한다.
 *
 * <p>partial unique index의 메타데이터만 조회하지 않고 충돌 INSERT를 실제로
 * 실행해 애플리케이션 사전 조회를 우회해도 DB가 불변식을 지키는지 확인한다.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RosterPersistenceIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
	private static final OffsetDateTime DB_NOW = NOW.atOffset(ZoneOffset.UTC);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StudentProfileRepository studentRepository;

	@Autowired
	private ClassGroupRepository classGroupRepository;

	@Autowired
	private TeacherStudentRelationshipRepository relationshipRepository;

	@Autowired
	private ClassEnrollmentRepository enrollmentRepository;

	@Test
	void storesRosterEntitiesWithUuidV7AndNullableGrade() {
		UUID teacherId = insertTeacher("teacher@example.com", "강사");
		StudentProfile student = studentRepository.saveAndFlush(
			StudentProfile.create("학생 별칭", null, NOW)
		);
		ClassGroup classGroup = classGroupRepository.saveAndFlush(
			ClassGroup.create(teacherId, "고1 A반", NOW)
		);
		relationshipRepository.saveAndFlush(TeacherStudentRelationship.start(
			teacherId,
			student.id(),
			NOW,
			NOW
		));
		ClassEnrollment enrollment = enrollmentRepository.saveAndFlush(
			ClassEnrollment.enroll(
				classGroup.id(),
				teacherId,
				student.id(),
				NOW,
				NOW
			)
		);

		assertThat(student.id().version()).isEqualTo(7);
		assertThat(classGroup.id().version()).isEqualTo(7);
		assertThat(enrollment.id().version()).isEqualTo(7);
		assertThat(studentRepository.findById(student.id())).isPresent();
	}

	@Test
	void databaseRejectsGradeZero() {
		assertThatThrownBy(() -> insertStudentWithGrade(0))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsGradeFour() {
		assertThatThrownBy(() -> insertStudentWithGrade(4))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsSecondActiveTeacherForStudent() {
		UUID firstTeacher = insertTeacher("first@example.com", "첫 강사");
		UUID secondTeacher = insertTeacher("second@example.com", "둘째 강사");
		UUID studentId = insertStudentWithGrade(1);
		insertRelationship(firstTeacher, studentId, "ACTIVE");

		assertThatThrownBy(() -> insertRelationship(
			secondTeacher,
			studentId,
			"ACTIVE"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsNewActiveTeacherAfterPreviousRelationshipEnded() {
		UUID firstTeacher = insertTeacher("first@example.com", "첫 강사");
		UUID secondTeacher = insertTeacher("second@example.com", "둘째 강사");
		UUID studentId = insertStudentWithGrade(1);
		UUID firstRelationship = insertRelationship(
			firstTeacher,
			studentId,
			"ACTIVE"
		);

		jdbcTemplate.update(
			"""
				UPDATE teacher_student_relationships
				SET status = 'ENDED', ended_at = ?
				WHERE id = ?
				""",
			DB_NOW.plusSeconds(60),
			firstRelationship
		);

		assertThat(insertRelationship(secondTeacher, studentId, "ACTIVE"))
			.isNotNull();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM teacher_student_relationships WHERE student_id = ?",
			Integer.class,
			studentId
		)).isEqualTo(2);
	}

	@Test
	void databaseRejectsSecondActiveEnrollmentForStudent() {
		UUID teacherId = insertTeacher("teacher@example.com", "강사");
		UUID studentId = insertStudentWithGrade(2);
		insertRelationship(teacherId, studentId, "ACTIVE");
		UUID firstClass = insertClass(teacherId, "첫 반");
		UUID secondClass = insertClass(teacherId, "둘째 반");
		insertEnrollment(
			firstClass,
			teacherId,
			studentId,
			"ACTIVE"
		);

		assertThatThrownBy(() -> insertEnrollment(
			secondClass,
			teacherId,
			studentId,
			"ACTIVE"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsNewActiveEnrollmentAfterPreviousEnrollmentEnded() {
		UUID teacherId = insertTeacher("teacher@example.com", "강사");
		UUID studentId = insertStudentWithGrade(2);
		insertRelationship(teacherId, studentId, "ACTIVE");
		UUID firstClass = insertClass(teacherId, "첫 반");
		UUID secondClass = insertClass(teacherId, "둘째 반");
		UUID firstEnrollment = insertEnrollment(
			firstClass,
			teacherId,
			studentId,
			"ACTIVE"
		);

		jdbcTemplate.update(
			"""
				UPDATE class_enrollments
				SET status = 'ENDED', ended_at = ?
				WHERE id = ?
				""",
			DB_NOW.plusSeconds(60),
			firstEnrollment
		);

		assertThat(insertEnrollment(
			secondClass,
			teacherId,
			studentId,
			"ACTIVE"
		)).isNotNull();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM class_enrollments WHERE student_id = ?",
			Integer.class,
			studentId
		)).isEqualTo(2);
	}

	@Test
	void rejectsEnrollmentWhenClassTeacherIsNotStudentsActiveTeacher() {
		UUID relationshipTeacher = insertTeacher(
			"relationship@example.com",
			"관계 강사"
		);
		UUID classTeacher = insertTeacher("class@example.com", "반 강사");
		UUID studentId = insertStudentWithGrade(3);
		insertRelationship(relationshipTeacher, studentId, "ACTIVE");
		UUID classId = insertClass(classTeacher, "다른 강사의 반");

		assertThatThrownBy(() -> insertEnrollment(
			classId,
			classTeacher,
			studentId,
			"ACTIVE"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertTeacher(String email, String displayName) {
		UUID accountId = UUID.randomUUID();
		UUID teacherId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO accounts (id, email, role, status, created_at)
				VALUES (?, ?, 'TEACHER', 'ACTIVE', ?)
				""",
			accountId,
			email,
			DB_NOW
		);
		jdbcTemplate.update(
			"""
				INSERT INTO teacher_profiles (
				    id, account_id, display_name, created_at, updated_at
				)
				VALUES (?, ?, ?, ?, ?)
				""",
			teacherId,
			accountId,
			displayName,
			DB_NOW,
			DB_NOW
		);
		return teacherId;
	}

	private UUID insertStudentWithGrade(Integer grade) {
		UUID studentId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO student_profiles (
				    id, alias, grade, created_at, updated_at
				)
				VALUES (?, '학생', ?, ?, ?)
				""",
			studentId,
			grade,
			DB_NOW,
			DB_NOW
		);
		return studentId;
	}

	private UUID insertClass(UUID teacherId, String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO class_groups (
				    id, teacher_id, name, status, created_at, updated_at
				)
				VALUES (?, ?, ?, 'ACTIVE', ?, ?)
				""",
			id,
			teacherId,
			name,
			DB_NOW,
			DB_NOW
		);
		return id;
	}

	private UUID insertRelationship(
		UUID teacherId,
		UUID studentId,
		String status
	) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO teacher_student_relationships (
				    id, teacher_id, student_id, status,
				    started_at, ended_at, created_at
				)
				VALUES (
				    ?, ?, ?, ?, ?,
				    CASE WHEN ? = 'ENDED' THEN ? ELSE NULL END,
				    ?
				)
				""",
			id,
			teacherId,
			studentId,
			status,
			DB_NOW,
			status,
			DB_NOW.plusSeconds(60),
			DB_NOW
		);
		return id;
	}

	private UUID insertEnrollment(
		UUID classId,
		UUID teacherId,
		UUID studentId,
		String status
	) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO class_enrollments (
				    id, class_group_id, teacher_id, student_id, status,
				    enrolled_at, ended_at, created_at
				)
				VALUES (
				    ?, ?, ?, ?, ?, ?,
				    CASE WHEN ? = 'ENDED' THEN ? ELSE NULL END,
				    ?
				)
				""",
			id,
			classId,
			teacherId,
			studentId,
			status,
			DB_NOW,
			status,
			DB_NOW.plusSeconds(60),
			DB_NOW
		);
		return id;
	}
}
