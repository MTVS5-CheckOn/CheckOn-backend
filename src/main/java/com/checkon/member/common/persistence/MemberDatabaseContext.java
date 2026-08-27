package com.checkon.member.common.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * member 요청의 RLS 주체를 현재 트랜잭션의 커넥션에 설정한다.
 *
 * <p>🔴 강사 테넌트 설정은 여기서 절대 건드리지 않는다. member 가 강사 경계를 열면 학생 요청이
 * 남의 반 데이터를 보게 된다 — 이 설계의 최상위 불변식이며 코드 규칙 G1 이 문자열로 막는다.</p>
 *
 * <p>커넥션은 요청이 끝나면 풀로 돌아가 다른 주체가 재사용한다. 그래서 세션 전역 SET 대신
 * 커밋·롤백 양쪽에서 PostgreSQL 이 자동 해제하는 {@code set_config(..., true)} 를 쓴다.</p>
 *
 * <p>실제 정책 검증은 대상 테이블이 생기는 PR2 에서 한다.</p>
 */
@Component
public class MemberDatabaseContext {

	public static final String ACCOUNT_SETTING = "checkon.current_account_id";
	public static final String STUDENT_SETTING = "checkon.current_student_id";
	public static final String PARENT_SETTING = "checkon.current_parent_id";

	public static final String STUDENT_SCOPE = "checkon.scope_student_id";
	public static final String ACCOUNT_SCOPE = "checkon.scope_account_id";
	public static final String PROBLEM_SET_SCOPE = "checkon.scope_problem_set_id";

	private static final String SET_CONFIG = "SELECT set_config(?, ?, true)";

	/** 학부모↔자녀 활성 관계 확인. 호출자(학부모) 컨텍스트의 기존 정책이 격리한다. */
	private static final String CHILD_LINK_QUERY = """
		SELECT student_id FROM parent_student_relationships
		WHERE parent_id = ? AND student_id = ? AND status = 'ACTIVE'
		""";

	/** 강사↔학생 담당 관계 확인. V33 정책이 격리한다. */
	private static final String TEACHER_LINK_QUERY = """
		SELECT student_id FROM teacher_student_relationships
		WHERE teacher_id = ? AND student_id = ? AND status IN ('ACTIVE', 'PAUSED')
		""";

	/** 열람자(학부모)가 대상 계정의 학생과 활성 관계인지 확인. */
	private static final String CHILD_ACCOUNT_QUERY = """
		SELECT student.account_id
		FROM parent_student_relationships link
		JOIN parent_profiles parent ON parent.id = link.parent_id
		JOIN student_profiles student ON student.id = link.student_id
		WHERE parent.account_id = ? AND student.account_id = ? AND link.status = 'ACTIVE'
		""";

	/**
	 * 🔴 학생↔학습지(problem_set) 소유 확인. {@code problem_assignments} 는 V38 의
	 * {@code problem_assignments_member_student_select} 가 학생 self 로 격리하므로,
	 * 호출자 컨텍스트가 학생일 때만 자기 assignment 가 보인다. 그 자기 assignment 에 붙은
	 * {@code problem_set_id} 만 열람 허용이다.
	 */
	private static final String STUDENT_PROBLEM_SET_QUERY = """
		SELECT problem_set_id FROM problem_assignments
		WHERE student_id = ? AND problem_set_id = ?
		LIMIT 1
		""";

	private final JdbcTemplate jdbcTemplate;

	public MemberDatabaseContext(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void setCurrentAccount(UUID accountId) {
		apply(ACCOUNT_SETTING, accountId);
	}

	public void setCurrentStudent(UUID studentProfileId) {
		apply(STUDENT_SETTING, studentProfileId);
	}

	public void setCurrentParent(UUID parentProfileId) {
		apply(PARENT_SETTING, parentProfileId);
	}

	/**
	 * 학부모가 <b>자기 자녀</b>를 열람하는 동안만 범위를 연다.
	 *
	 * <p>🔴 세터를 공개 API 로 두지 않는다. {@code setScopeStudentId(UUID)} 같은 형태는 인자를
	 * 그대로 믿으므로, 관계를 방금 INSERT 한 호출부에서 "되읽기가 군더더기"로 보여 확인을 건너뛰게
	 * 된다. 그 순간 RLS 를 스스로 뚫는 우회로가 된다.</p>
	 *
	 * <p>확인과 세팅을 한 메서드에 묶어 <b>확인을 건너뛸 문법적 방법을 없앤다.</b>
	 * 확인에 실패하면 세션에 아무것도 들어가지 않는다.</p>
	 *
	 * <p>같은 트랜잭션에서 방금 만든 관계도 보이므로, 자녀 등록 직후 호출해도 통과한다.</p>
	 */
	public <T> T withVerifiedChildScope(UUID parentId, UUID studentId, Supplier<T> action) {
		return withVerifiedScope(STUDENT_SCOPE, studentId, action, CHILD_LINK_QUERY,
			parentId, studentId);
	}

	/** 강사가 <b>담당 학생</b>을 열람하는 동안만 범위를 연다. 위와 같은 이유로 세터를 열지 않는다. */
	public <T> T withVerifiedStudentScope(UUID teacherId, UUID studentId, Supplier<T> action) {
		return withVerifiedScope(STUDENT_SCOPE, studentId, action, TEACHER_LINK_QUERY,
			teacherId, studentId);
	}

	/**
	 * 열람자가 <b>접근이 확인된 계정</b>의 표시 이름 등을 읽는 동안만 범위를 연다.
	 *
	 * <p>본인이면 관계 확인 없이 통과한다. 남의 계정이면 학부모↔자녀 관계로만 열린다 —
	 * 자녀의 {@code account_id} 는 {@code student_profiles} 를 거쳐 확인한다.</p>
	 */
	public <T> T withVerifiedAccountScope(
		UUID viewerAccountId,
		UUID targetAccountId,
		Supplier<T> action
	) {
		Objects.requireNonNull(viewerAccountId, "viewerAccountId must not be null");
		if (viewerAccountId.equals(targetAccountId)) {
			return withScope(ACCOUNT_SCOPE, targetAccountId, action);
		}
		return withVerifiedScope(ACCOUNT_SCOPE, targetAccountId, action, CHILD_ACCOUNT_QUERY,
			viewerAccountId, targetAccountId);
	}

	/**
	 * 학생이 <b>자기 assignment 가 참조하는 학습지</b>를 열람하는 동안만 범위를 연다.
	 *
	 * <p>🔴 <b>세터를 두지 않는 이유</b>는 {@link #withVerifiedChildScope} 와 같다 — 호출부에서
	 * "이미 확인했으니 그냥 넣자"가 되는 순간 RLS 를 스스로 뚫는 우회로가 된다. 소유는
	 * {@code problem_assignments} 학생 self 정책(V38:154)이 이미 격리한 결과에서 온다.</p>
	 *
	 * <p>V38:176 의 {@code saved_problem_set_items_member_student_select} 가 요구하는
	 * {@code current_checkon_scope_problem_set_id()} 를 세팅한다. 밖에서 부르면 예외가 아니라
	 * <b>0행</b>이다(설계 §6-4-3). 확인에 실패하면 세션에 아무것도 들어가지 않는다.</p>
	 *
	 * <p>🔴 <b>목록형 조회는 행마다 이 메서드를 한 번씩 부른다</b>(MB-39) — 스코프 함수가 한 번에
	 * 한 값이라 목록 전체를 한꺼번에 열어두는 방법이 없다. 반드시 <b>같은 트랜잭션 안에서</b>
	 * 반복해야 왕복이 1회로 유지된다.</p>
	 */
	public <T> T withVerifiedProblemSetScope(
		UUID studentId, UUID problemSetId, Supplier<T> action
	) {
		return withVerifiedScope(PROBLEM_SET_SCOPE, problemSetId, action,
			STUDENT_PROBLEM_SET_QUERY, studentId, problemSetId);
	}

	private <T> T withVerifiedScope(
		String setting,
		UUID value,
		Supplier<T> action,
		String verificationQuery,
		Object... arguments
	) {
		Objects.requireNonNull(value, () -> setting + " must not be null");
		requireTransaction();
		// 🔴 관계 확인은 호출자 자신의 컨텍스트로 돈다. 그 테이블의 기존 정책이 이미 격리한다.
		//    확인을 통과한 id 만 아래에서 세션에 들어간다.
		List<UUID> verified = jdbcTemplate.queryForList(verificationQuery, UUID.class, arguments);
		if (verified.isEmpty()) {
			throw new MemberScopeDeniedException(setting);
		}
		return withScope(setting, value, action);
	}

	private <T> T withScope(String setting, UUID value, Supplier<T> action) {
		requireTransaction();
		apply(setting, value);
		try {
			return action.get();
		}
		finally {
			// 트랜잭션 로컬이라 커밋·롤백에서 자동 해제되지만, 같은 트랜잭션 안에서 범위가
			// 다음 작업으로 새지 않도록 명시적으로 되돌린다.
			clear(setting);
		}
	}

	private void clear(String setting) {
		jdbcTemplate.queryForObject(SET_CONFIG, String.class, setting, "");
	}

	private void requireTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
				"Member database context requires an active database transaction"
			);
		}
	}

	private void apply(String setting, UUID value) {
		Objects.requireNonNull(value, () -> setting + " must not be null");
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
				"Member database context requires an active database transaction"
			);
		}
		jdbcTemplate.queryForObject(SET_CONFIG, String.class, setting, value.toString());
	}
}
