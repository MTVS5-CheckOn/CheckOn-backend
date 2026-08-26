package com.checkon.member.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.member.integration.problem.PublishedItemSnapshot;
import com.checkon.member.integration.problem.PublishedWorksheetAdapter;
import com.checkon.member.learning.domain.MemberAttempt;
import com.checkon.member.learning.domain.MemberAttemptAnswer;
import com.checkon.member.learning.domain.MemberAttemptEventType;
import com.checkon.member.learning.domain.MemberAttemptItemRow;
import com.checkon.member.learning.domain.MemberAttemptStatus;
import com.checkon.member.learning.domain.MemberLearningSession;
import com.checkon.member.learning.domain.StudentAssignmentRow;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptAnswerRepository;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptEventRepository;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptItemRepository;
import com.checkon.member.learning.infrastructure.persistence.MemberAttemptRepository;
import com.checkon.member.learning.infrastructure.persistence.MemberLearningSessionRepository;
import com.checkon.member.learning.infrastructure.persistence.StudentWorksheetQueryRepository;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;

/**
 * S1 리포지토리·어댑터의 최소 계약. 🔴 <b>제한 역할</b> 위에서 돈다 ({@link MembershipRlsEnforcedSupport}) —
 * superuser 로 돌면 RLS 정책이 통째로 우회돼(MB-34) "안 보인다" 단언이 아무것도 증명하지 못한다.
 *
 * <p>🔴 「전제」 단언 2개를 먼저 둔다 — ① 앱 커넥션이 정말 RLS 대상인가 ② 픽스처 행이 실제로 있는가
 * (설계 §6-4-3 · NEXT.md §1 「제한 역할 + 실제 행 + 전제 2개」). 이게 없으면 정책 파괴가 red 를
 * 내지 않는다(PR3 결함 재발 방지).</p>
 *
 * <p>🔴 <b>G17 승인 조합만 사용</b>한다 — {@code MembershipRlsEnforcedSupport} 와 같은 3개 프로퍼티.
 * 새 조합이 필요하면 {@code MemberCodeRuleTest} G17 을 먼저 고쳐야 한다.</p>
 *
 * <p>🔴 컨트롤러가 없어 서비스 없이 저장소를 직접 부른다. 트랜잭션 경계는 {@link TransactionTemplate}
 * 이 만든다 — {@code set_config(..., true)} 는 트랜잭션 로컬이라(설계 §6-4-4) 밖으로 새면
 * <b>0행</b>이라 조용하다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("dev")
class AttemptRepositoryIntegrationTest extends MembershipRlsEnforcedSupport {

	@Autowired MemberAttemptRepository attemptRepository;
	@Autowired MemberAttemptItemRepository itemRepository;
	@Autowired MemberAttemptAnswerRepository answerRepository;
	@Autowired MemberAttemptEventRepository eventRepository;
	@Autowired MemberLearningSessionRepository sessionRepository;
	@Autowired StudentWorksheetQueryRepository worksheetQueryRepository;
	@Autowired PublishedWorksheetAdapter publishedWorksheetAdapter;
	@Autowired JdbcTemplate appJdbcTemplate;
	@Autowired PlatformTransactionManager transactionManager;

	private TransactionTemplate transaction;

	private UUID teacherId;
	private UUID studentId;
	private UUID otherStudentId;
	private UUID problemSetId;
	private UUID assignmentId;
	private UUID itemId;

	@BeforeEach
	void setUp() {
		JdbcTemplate admin = adminJdbcTemplate();
		clearFixtures(admin);
		transaction = new TransactionTemplate(transactionManager);

		OffsetDateTime now = OffsetDateTime.now();
		teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);

		UUID studentAccount = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentId = insertStudentProfile(admin, studentAccount, "박학생", now);

		UUID otherAccount = insertAccount(admin, "other@example.com", "STUDENT", now);
		otherStudentId = insertStudentProfile(admin, otherAccount, "이학생", now);

		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentId, now, now);

		UUID requestId = insertProblemRequest(admin, teacherId, studentId, now);
		problemSetId = insertProblemSet(admin, teacherId, requestId, now);
		itemId = insertProblemItem(admin, teacherId, requestId, now);
		insertSavedItem(admin, teacherId, requestId, problemSetId, itemId, 1);
		assignmentId = insertAssignment(admin, teacherId, requestId, problemSetId, studentId, now);
	}

	// ─────────────────────────── 전제 (2건) ───────────────────────────

	@Test
	@DisplayName("🔴 전제 — 앱 커넥션이 정말 RLS 대상이다 (super/bypassrls = false/false)")
	void appConnectionIsSubjectToRls() {
		assertThat(applicationRolePrivileges())
			.as("superuser 나 bypassrls 면 아래 단언이 전부 참이 되어 아무것도 증명하지 못한다")
			.isEqualTo("false/false");
	}

	@Test
	@DisplayName("🔴 전제 — 픽스처 행이 실제로 있다 (관리자 눈에 assignment 1건)")
	void fixtureRowsExist() {
		Integer count = adminJdbcTemplate().queryForObject(
			"SELECT count(*) FROM problem_assignments WHERE id = ?", Integer.class, assignmentId);
		assertThat(count).as("행이 없으면 아래 단언이 RLS 와 무관하게 통과한다").isEqualTo(1);
	}

	// ─────────────────────────── attempt CRUD ───────────────────────────

	@Test
	@DisplayName("attempt 를 학생 컨텍스트로 INSERT 하고 findOpen 이 그대로 돌려준다")
	void insertAndFindOpenReturnsAttempt() {
		Instant now = Instant.parse("2026-08-26T09:00:00Z");
		MemberAttempt attempt = newAttempt(now);

		transaction.executeWithoutResult(status -> {
			setStudent(studentId);
			attemptRepository.insert(attempt);
		});

		Optional<MemberAttempt> found = transaction.execute(status -> {
			setStudent(studentId);
			return attemptRepository.findOpen(studentId, assignmentId);
		});
		assertThat(found).isPresent();
		assertThat(found.get().id()).isEqualTo(attempt.id());
		assertThat(found.get().status()).isEqualTo(MemberAttemptStatus.IN_PROGRESS);
		assertThat(found.get().snapshotHash()).isEqualTo(attempt.snapshotHash());
	}

	@Test
	@DisplayName("🔴 다른 학생 컨텍스트에서는 자기 것이 아닌 attempt 가 0건이다 (RLS)")
	void otherStudentSeesZeroRows() {
		Instant now = Instant.parse("2026-08-26T09:05:00Z");
		MemberAttempt attempt = newAttempt(now);
		transaction.executeWithoutResult(status -> {
			setStudent(studentId);
			attemptRepository.insert(attempt);
		});

		Optional<MemberAttempt> otherView = transaction.execute(status -> {
			setStudent(otherStudentId);
			return attemptRepository.findOpen(studentId, assignmentId);
		});
		Optional<MemberAttempt> noContext = transaction.execute(status -> {
			return attemptRepository.findOpen(studentId, assignmentId);
		});

		assertThat(otherView).as("남의 attempt 가 보이면 정책이 뚫린 것이다").isEmpty();
		assertThat(noContext).as("컨텍스트 없으면 예외가 아니라 빈 결과다 (§6-4-3)").isEmpty();
	}

	// ─────────────────────────── items · answers · events · sessions ───────────────────────────

	@Test
	@DisplayName("items·answers·events·session 을 학생 컨텍스트로 넣고 그대로 다시 읽는다")
	void writeAndReadChildTables() {
		Instant now = Instant.parse("2026-08-26T09:10:00Z");
		MemberAttempt attempt = newAttempt(now);

		transaction.executeWithoutResult(status -> {
			setStudent(studentId);
			attemptRepository.insert(attempt);
			itemRepository.insertAll(List.of(new MemberAttemptItemRow(
				attempt.id(), itemId, 1, "본문", null,
				"[{\"position\":1,\"content\":\"보기\"}]", 1, "해설",
				"reading", "fact", "L1")));
			answerRepository.insertPlaceholders(attempt.id(), List.of(itemId), now);
			eventRepository.insert(attempt.id(), MemberAttemptEventType.STARTED,
				null, null, now);
		});

		List<MemberAttemptItemRow> items = transaction.execute(status -> {
			setStudent(studentId);
			return itemRepository.findByAttempt(attempt.id());
		});
		List<MemberAttemptAnswer> answers = transaction.execute(status -> {
			setStudent(studentId);
			return answerRepository.findByAttempt(attempt.id());
		});

		assertThat(items).hasSize(1);
		assertThat(items.get(0).correctNo()).isEqualTo(1);
		assertThat(items.get(0).areaTag()).isEqualTo("reading");
		assertThat(answers).hasSize(1);
		assertThat(answers.get(0).selectedNo()).as("미응답 placeholder 는 NULL 이다").isNull();
		assertThat(answers.get(0).revision()).isZero();

		Integer eventCount = adminJdbcTemplate().queryForObject(
			"SELECT count(*) FROM member_attempt_events WHERE attempt_id = ?",
			Integer.class, attempt.id());
		assertThat(eventCount).isEqualTo(1);

		// 세션 요약도 학생 self insert 로 넣히는지 최소 확인 (제출 트랜잭션이 S4 지만
		// 리포지토리 계약을 미리 잠근다).
		MemberLearningSession session = new MemberLearningSession(
			UUID.randomUUID(), attempt.id(), studentId, teacherId, assignmentId,
			"학습지 1문항 · 2026-08-26", 1, 1, 0, null, now);
		transaction.executeWithoutResult(status -> {
			setStudent(studentId);
			sessionRepository.insert(session);
		});
		Integer sessionCount = adminJdbcTemplate().queryForObject(
			"SELECT count(*) FROM member_learning_sessions WHERE attempt_id = ?",
			Integer.class, attempt.id());
		assertThat(sessionCount).isEqualTo(1);
	}

	// ─────────────────────────── PublishedWorksheetAdapter ───────────────────────────

	@Test
	@DisplayName("adapter — 스코프를 열면 스냅샷을 파싱해 돌려주고, 열지 않으면 빈 리스트다")
	void adapterHonoursScope() {
		List<PublishedItemSnapshot> withoutScope = transaction.execute(status -> {
			setStudent(studentId);
			return publishedWorksheetAdapter.snapshotsOf(problemSetId);
		});
		List<PublishedItemSnapshot> withScope = transaction.execute(status -> {
			setStudent(studentId);
			setScope(problemSetId);
			return publishedWorksheetAdapter.snapshotsOf(problemSetId);
		});

		assertThat(withoutScope).as("스코프 없으면 정책이 0행 (예외 아님)").isEmpty();
		assertThat(withScope).hasSize(1);
		PublishedItemSnapshot snap = withScope.get(0);
		assertThat(snap.itemId()).isEqualTo(itemId);
		assertThat(snap.correctNo()).isEqualTo(1);
		assertThat(snap.areaTag()).isEqualTo("reading");
		assertThat(snap.typeTag()).isEqualTo("fact");
		assertThat(snap.skillNodeId()).isEqualTo("L1");
		assertThat(snap.options()).hasSize(1);
		assertThat(snap.options().get(0).position()).isEqualTo(1);
		assertThat(snap.options().get(0).content()).isEqualTo("보기");
	}

	// ─────────────────────────── 조회용 리포지토리 ───────────────────────────

	@Test
	@DisplayName("조회 리포 — 학생 컨텍스트에서 자기 assignment 만 보인다")
	void queryRepositoryReturnsOwnAssignments() {
		List<StudentAssignmentRow> rows = transaction.execute(status -> {
			setStudent(studentId);
			return worksheetQueryRepository.findAssignmentsByStudent(studentId);
		});
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).assignmentId()).isEqualTo(assignmentId);
		assertThat(rows.get(0).problemSetId()).isEqualTo(problemSetId);
	}

	// ─────────────────────────── 헬퍼 ───────────────────────────

	private MemberAttempt newAttempt(Instant now) {
		return new MemberAttempt(
			UUID.randomUUID(), studentId, assignmentId, teacherId,
			MemberAttemptStatus.IN_PROGRESS, 0,
			"sha256:0000000000000000000000000000000000000000000000000000000000000000",
			1, 0, null, now, null, null, null);
	}

	private void setStudent(UUID id) {
		appJdbcTemplate.queryForObject(
			"SELECT set_config('checkon.current_student_id', ?, true)",
			String.class, id.toString());
	}

	private void setScope(UUID id) {
		appJdbcTemplate.queryForObject(
			"SELECT set_config('checkon.scope_problem_set_id', ?, true)",
			String.class, id.toString());
	}

	/**
	 * 🔴 부모 클래스의 {@code insertStudent(StudentFixture)} 를 못 쓰는 이유 — 그 record 가
	 * {@code protected} 라 다른 패키지에서는 <b>subclass 여도</b> 생성자 호출이 막힌다(JLS 6.6.2).
	 * 부모 helper 를 public 으로 바꾸면 무접촉 범위(승우님 소유 아님이지만 팀 공유 지원 클래스)를
	 * 흔들 소지가 있어, 이 테스트는 필요한 최소 픽스처만 로컬에서 직접 넣는다.
	 */
	private static UUID insertStudentProfile(
		JdbcTemplate admin, UUID accountId, String alias, OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO student_profiles (id, account_id, alias, account_linked_at,"
			+ " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
			id, accountId, alias, now, now, now);
		return id;
	}

	private static UUID insertProblemRequest(
		JdbcTemplate admin, UUID teacher, UUID student, OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_generation_requests (id, teacher_id, tenant_alias,"
			+ " target_kind, student_id, target_ref, ai_idempotency_key, snapshot_hash,"
			+ " request_payload, status, requested_at, updated_at)"
			+ " VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)",
			id, teacher, "tn_" + hex32(), student, "st_" + hex32(),
			"pg_" + hex32(), "sha256:" + hex32() + hex32(), now, now);
		return id;
	}

	private static UUID insertProblemSet(
		JdbcTemplate admin, UUID teacher, UUID requestId, OffsetDateTime now
	) {
		UUID setId = UUID.randomUUID();
		admin.update("INSERT INTO saved_problem_sets (id, teacher_id, problem_request_id, status,"
			+ " saved_at, updated_at) VALUES (?, ?, ?, 'SAVED', ?, ?)",
			setId, teacher, requestId, now, now);
		return setId;
	}

	private static UUID insertProblemItem(
		JdbcTemplate admin, UUID teacher, UUID requestId, OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_generation_items (id, teacher_id, problem_request_id,"
			+ " ordinal, stem, validation_status, raw_payload, created_at, updated_at)"
			+ " VALUES (?, ?, ?, 1, '문항', 'PASSED', '{}'::jsonb, ?, ?)",
			id, teacher, requestId, now, now);
		return id;
	}

	private static void insertSavedItem(
		JdbcTemplate admin, UUID teacher, UUID requestId, UUID setId, UUID itemId, int ordinal
	) {
		String snapshot = String.format("""
			{"itemId":"%s","ordinal":%d,"skillNodeId":"L1","areaTag":"reading",
			 "typeTag":"fact","stem":"본문","passage":null,"correctNo":1,
			 "correctAnswerText":"보기","explanation":"해설","validationStatus":"PASSED",
			 "options":[{"position":1,"content":"보기","whyWrong":null,
			             "misconceptionTag":null}]}
			""", itemId, ordinal);
		admin.update("INSERT INTO saved_problem_set_items (problem_set_id, item_id, teacher_id,"
			+ " problem_request_id, ordinal, item_snapshot) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
			setId, itemId, teacher, requestId, ordinal, snapshot);
	}

	private static UUID insertAssignment(
		JdbcTemplate admin, UUID teacher, UUID requestId, UUID setId, UUID student,
		OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO problem_assignments (id, teacher_id, problem_request_id,"
			+ " problem_set_id, student_id, status, published_at)"
			+ " VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?)",
			id, teacher, requestId, setId, student, now);
		return id;
	}

	private static String hex32() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
