package com.checkon.roster.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
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
			ClassGroup.create(teacherId, "고1 A반", "수학", null, NOW)
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
	void databaseRejectsBlankClassSubject() {
		UUID teacherId = insertTeacher("subject@example.com", "과목 강사");
		UUID classId = insertClass(teacherId, "과목 반");

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE class_groups SET subject = '   ' WHERE id = ?",
			classId
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsOversizedClassMemo() {
		UUID teacherId = insertTeacher("memo@example.com", "메모 강사");
		UUID classId = insertClass(teacherId, "메모 반");

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE class_groups SET memo = ? WHERE id = ?",
			"가".repeat(1001),
			classId
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRequiresSubjectForNewClassRows() {
		UUID teacherId = insertTeacher("required@example.com", "필수 과목 강사");

		assertThatThrownBy(() -> jdbcTemplate.update(
			"""
				INSERT INTO class_groups (
				    id, teacher_id, name, status, created_at, updated_at
				) VALUES (?, ?, '과목 없는 반', 'ACTIVE', ?, ?)
				""",
			UUID.randomUUID(), teacherId, DB_NOW, DB_NOW
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Given 한 학생과 서로 다른 강사일 때 When 활성 관계를 각각 만들면 Then 두 테넌트 관계를 모두 허용한다")
	void allowsActiveRelationshipsWithDifferentTeachersForStudent() {
		UUID firstTeacher = insertTeacher("first@example.com", "첫 강사");
		UUID secondTeacher = insertTeacher("second@example.com", "둘째 강사");
		UUID studentId = insertStudentWithGrade(1);
		insertRelationship(firstTeacher, studentId, "ACTIVE");

		assertThat(insertRelationship(secondTeacher, studentId, "ACTIVE"))
			.isNotNull();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM teacher_student_relationships WHERE student_id = ? AND status = 'ACTIVE'",
			Integer.class,
			studentId
		)).isEqualTo(2);
	}

	@Test
	@DisplayName("Given 같은 강사와 학생의 현재 관계일 때 When 두 번째 관계를 만들면 Then DB가 중복을 거절한다")
	void rejectsSecondCurrentRelationshipForSameTeacherAndStudent() {
		UUID teacherId = insertTeacher("teacher@example.com", "강사");
		UUID studentId = insertStudentWithGrade(1);
		insertRelationship(teacherId, studentId, "PAUSED");

		assertThatThrownBy(() -> insertRelationship(
			teacherId,
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
	@DisplayName("Given 같은 강사의 한 학생일 때 When 두 활성 반에 등록하면 Then DB가 두 번째 등록을 거절한다")
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
	@DisplayName("Given 한 학생과 서로 다른 강사일 때 When 각 강사의 반에 등록하면 Then 두 활성 소속을 모두 허용한다")
	void allowsActiveClassEnrollmentPerTeacherForStudent() {
		UUID firstTeacher = insertTeacher("first-class@example.com", "첫 강사");
		UUID secondTeacher = insertTeacher("second-class@example.com", "둘째 강사");
		UUID studentId = insertStudentWithGrade(2);
		insertRelationship(firstTeacher, studentId, "ACTIVE");
		insertRelationship(secondTeacher, studentId, "ACTIVE");
		UUID firstClass = insertClass(firstTeacher, "첫 강사 반");
		UUID secondClass = insertClass(secondTeacher, "둘째 강사 반");

		insertEnrollment(firstClass, firstTeacher, studentId, "ACTIVE");
		insertEnrollment(secondClass, secondTeacher, studentId, "ACTIVE");

		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM class_enrollments WHERE student_id = ? AND status = 'ACTIVE'",
			Integer.class,
			studentId
		)).isEqualTo(2);
	}

	@Test
	@DisplayName("Given 한 학부모와 서로 다른 강사일 때 When 활성 관계를 만들면 Then 여러 강사 연결을 허용하고 같은 조합 중복은 거절한다")
	void allowsParentAcrossTeachersAndRejectsDuplicateActivePair() {
		UUID firstTeacher = insertTeacher("parent-first@example.com", "첫 강사");
		UUID secondTeacher = insertTeacher("parent-second@example.com", "둘째 강사");
		UUID parentId = insertParent("parent@example.com");

		insertParentTeacherRelationship(parentId, firstTeacher, "ACTIVE");
		insertParentTeacherRelationship(parentId, secondTeacher, "ACTIVE");

		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM parent_teacher_relationships WHERE parent_id = ? AND status = 'ACTIVE'",
			Integer.class,
			parentId
		)).isEqualTo(2);
		assertThatThrownBy(() -> insertParentTeacherRelationship(
			parentId,
			firstTeacher,
			"ACTIVE"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Given 한 학생에게 활성 학부모가 있을 때 When 다른 학부모를 연결하면 Then DB가 두 번째 연결을 거절한다")
	void rejectsSecondActiveParentForStudent() {
		UUID firstParent = insertParent("first-parent@example.com");
		UUID secondParent = insertParent("second-parent@example.com");
		UUID studentId = insertStudentWithGrade(1);
		insertParentStudentRelationship(firstParent, studentId, "ACTIVE");

		assertThatThrownBy(() -> insertParentStudentRelationship(
			secondParent,
			studentId,
			"ACTIVE"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Given 한 학부모와 여러 학생일 때 When 각 자녀를 연결하면 Then 모두 허용한다")
	void allowsParentToHaveMultipleStudents() {
		UUID parentId = insertParent("multi-child-parent@example.com");
		UUID firstStudent = insertStudentWithGrade(1);
		UUID secondStudent = insertStudentWithGrade(2);

		insertParentStudentRelationship(parentId, firstStudent, "ACTIVE");
		insertParentStudentRelationship(parentId, secondStudent, "ACTIVE");

		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM parent_student_relationships WHERE parent_id = ? AND status = 'ACTIVE'",
			Integer.class,
			parentId
		)).isEqualTo(2);
	}

	@Test
	@DisplayName("Given 강사 계정일 때 When 학부모 프로필에 연결하면 Then DB가 역할 불일치를 거절한다")
	void rejectsParentProfileForNonParentAccount() {
		UUID teacherId = insertTeacher("not-parent@example.com", "강사");
		UUID accountId = jdbcTemplate.queryForObject(
			"SELECT account_id FROM teacher_profiles WHERE id = ?",
			UUID.class,
			teacherId
		);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO parent_profiles (id, account_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
			UUID.randomUUID(), accountId, DB_NOW, DB_NOW
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

	@Test
	void databaseRejectsArchivingAClassWithActiveEnrollments() {
		UUID teacherId = insertTeacher("archive@example.com", "보관 강사");
		UUID studentId = insertStudentWithGrade(1);
		insertRelationship(teacherId, studentId, "ACTIVE");
		UUID classId = insertClass(teacherId, "보관 대상 반");
		insertEnrollment(classId, teacherId, studentId, "ACTIVE");

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE class_groups SET status = 'ARCHIVED' WHERE id = ?",
			classId
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsActiveEnrollmentInAnArchivedClass() {
		UUID teacherId = insertTeacher("archived@example.com", "보관 강사");
		UUID studentId = insertStudentWithGrade(1);
		insertRelationship(teacherId, studentId, "ACTIVE");
		UUID classId = insertClass(teacherId, "이미 보관된 반");
		jdbcTemplate.update(
			"UPDATE class_groups SET status = 'ARCHIVED' WHERE id = ?",
			classId
		);

		assertThatThrownBy(() -> insertEnrollment(
			classId, teacherId, studentId, "ACTIVE"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsReactivatingAnArchivedClass() {
		UUID teacherId = insertTeacher("reactivate@example.com", "재활성 강사");
		UUID classId = insertClass(teacherId, "보관 완료 반");
		jdbcTemplate.update(
			"UPDATE class_groups SET status = 'ARCHIVED' WHERE id = ?",
			classId
		);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE class_groups SET status = 'ACTIVE' WHERE id = ?",
			classId
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseAllowsArchiveAfterEnrollmentEnded() {
		UUID teacherId = insertTeacher("ended@example.com", "종료 강사");
		UUID studentId = insertStudentWithGrade(1);
		insertRelationship(teacherId, studentId, "ACTIVE");
		UUID classId = insertClass(teacherId, "종료 완료 반");
		UUID enrollmentId = insertEnrollment(
			classId, teacherId, studentId, "ACTIVE"
		);
		jdbcTemplate.update("""
			UPDATE class_enrollments
			SET status = 'ENDED', ended_at = ?
			WHERE id = ?
			""", DB_NOW.plusSeconds(1), enrollmentId);

		assertThat(jdbcTemplate.update(
			"UPDATE class_groups SET status = 'ARCHIVED' WHERE id = ?",
			classId
		)).isOne();
	}

	@Test
	void requiresTeacherBoundaryForClassRelationshipAndEnrollmentLookup() {
		UUID teacherA = insertTeacher("tenant-a@example.com", "강사 A");
		UUID teacherB = insertTeacher("tenant-b@example.com", "강사 B");
		UUID studentId = insertStudentWithGrade(1);
		UUID relationshipId = insertRelationship(teacherA, studentId, "ACTIVE");
		UUID classId = insertClass(teacherA, "A 소유 반");
		UUID enrollmentId = insertEnrollment(
			classId,
			teacherA,
			studentId,
			"ACTIVE"
		);

		// 명시적 teacherId 조회는 이해하기 쉬운 소유권 경계이며, V7 RLS는
		// 누락된 조건이나 직접 SQL을 막는 별도의 최종 방어다.
		assertThat(classGroupRepository.findByIdAndTeacherId(classId, teacherA))
			.isPresent();
		assertThat(classGroupRepository.findByIdAndTeacherId(classId, teacherB))
			.isEmpty();
		assertThat(relationshipRepository.findByIdAndTeacherId(
			relationshipId,
			teacherA
		)).isPresent();
		assertThat(relationshipRepository.findByIdAndTeacherId(
			relationshipId,
			teacherB
		)).isEmpty();
		assertThat(enrollmentRepository.findByIdAndTeacherId(
			enrollmentId,
			teacherA
		)).isPresent();
		assertThat(enrollmentRepository.findByIdAndTeacherId(
			enrollmentId,
			teacherB
		)).isEmpty();
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

	private UUID insertParent(String email) {
		UUID accountId = UUID.randomUUID();
		UUID parentId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO accounts (id, email, role, status, created_at) VALUES (?, ?, 'PARENT', 'ACTIVE', ?)",
			accountId,
			email,
			DB_NOW
		);
		jdbcTemplate.update(
			"INSERT INTO parent_profiles (id, account_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
			parentId,
			accountId,
			DB_NOW,
			DB_NOW
		);
		return parentId;
	}

	private UUID insertParentTeacherRelationship(
		UUID parentId,
		UUID teacherId,
		String status
	) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO parent_teacher_relationships (
				    id, parent_id, teacher_id, status, started_at, ended_at, created_at
				) VALUES (?, ?, ?, ?, ?, CASE WHEN ? = 'ENDED' THEN ? ELSE NULL END, ?)
				""",
			id, parentId, teacherId, status, DB_NOW, status, DB_NOW.plusSeconds(60), DB_NOW
		);
		return id;
	}

	private UUID insertParentStudentRelationship(
		UUID parentId,
		UUID studentId,
		String status
	) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO parent_student_relationships (
				    id, parent_id, student_id, status, started_at, ended_at, created_at
				) VALUES (?, ?, ?, ?, ?, CASE WHEN ? = 'ENDED' THEN ? ELSE NULL END, ?)
				""",
			id, parentId, studentId, status, DB_NOW, status, DB_NOW.plusSeconds(60), DB_NOW
		);
		return id;
	}

	private UUID insertClass(UUID teacherId, String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO class_groups (
				    id, teacher_id, name, subject, status, created_at, updated_at
				)
				VALUES (?, ?, ?, '수학', 'ACTIVE', ?, ?)
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
