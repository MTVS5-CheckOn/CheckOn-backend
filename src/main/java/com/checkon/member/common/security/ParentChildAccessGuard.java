package com.checkon.member.common.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.integration.roster.RosterRelationshipPort;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;

/**
 * 학부모가 자녀의 <b>어느 강사 데이터</b>를 볼 수 있는지 판정하는 한 곳.
 *
 * <p>설계 §9-2 는 「{@code teacherId} 는 권한이 아니라 필터다. 서버가 {@code parent↔student}
 * 와 {@code student↔teacher} 관계 교집합을 매 요청 재검증한다」고 못 박았다. 그 재검증을
 * 컨트롤러·서비스마다 흩어 두면 한 곳만 빠져도 조용히 새므로 여기 하나로 모은다.</p>
 *
 * <p>두 단계다:</p>
 * <ol>
 *   <li>{@link MemberDatabaseContext#withVerifiedChildScope} 가
 *       {@code parent_student_relationships} 를 <b>학부모 컨텍스트로</b> 읽어 활성 관계를
 *       확인하고, 확인된 {@code studentId} 만 범위 세션 변수에 넣는다. 실패하면 세션에
 *       아무것도 안 들어가고 {@code MemberScopeDeniedException} 이 나간다 →
 *       {@code MemberExceptionHandler} 가 <b>404</b> 로 번역한다.</li>
 *   <li>범위가 열린 <b>그 안에서</b> {@code teacher_student_relationships} 를 읽어 자녀의 활성
 *       강사 집합을 얻는다. V40 의
 *       {@code teacher_student_relationships_member_parent_scope_select}(MB-36)가 범위 세션
 *       변수로 이 조회를 연다 — 범위 밖에서 부르면 예외가 아니라 <b>빈 배열</b>이다.</li>
 * </ol>
 *
 * <p>🔴 <b>이 집합에 없는 강사의 보고서는 없는 것이다</b>(404). 부재와 권한 없음을 구분하지
 * 않는다(설계 §6-4 불변식 3).</p>
 *
 * <p>🔴 {@code findTeachersOfChild} 는 {@code ACTIVE} 만 돌려준다. 지시서 §2 는
 * {@code ACTIVE|PAUSED} 라고 적었지만, 코드베이스에 이미 있는 유일한 조회기가 ACTIVE 전용이고
 * (PR5b 판단: 「PAUSED 관계는 학생·학부모 화면에 보일 이유가 없다」) 같은 뜻의 조회기를 두 벌
 * 두면 갈린다. 더 좁은 쪽이라 fail-closed 다 — MB-56 에 등재했다.</p>
 *
 * <p>🔴 관계 종료 후 과거 보고서 노출은 MB-08 로 미확정이다. 현재는 여기서 fail-closed 404 다.
 * 값을 바꿀 지점이 이 클래스 하나다.</p>
 */
// TODO(MB-08): 관계 종료 후 과거 학습기록·보고서 열람이 확정되면 이 클래스에서만 고친다.
//              현재 값은 분기표 §7 의 「불가(404)」다 — 잠정 행이라 구현하지 않았다.
@Component
public class ParentChildAccessGuard {

	private final MemberDatabaseContext databaseContext;
	private final RosterRelationshipPort rosterRelationships;

	public ParentChildAccessGuard(
		MemberDatabaseContext databaseContext,
		RosterRelationshipPort rosterRelationships
	) {
		this.databaseContext = databaseContext;
		this.rosterRelationships = rosterRelationships;
	}

	/**
	 * 주체 컨텍스트를 열고, 자녀 관계를 재검증하고, 그 안에서 작업을 돌린다.
	 *
	 * <p>🔴 반드시 <b>트랜잭션 안</b>에서 부른다. 세션 변수는 트랜잭션 로컬이라
	 * 앞 트랜잭션에서 연 것은 여기 없다(설계 §6-4-4).</p>
	 *
	 * @param action 열린 범위 안에서 돌 작업. 인자로 자녀의 활성 강사 집합을 받는다
	 */
	public <T> T withVerifiedChild(
		MemberSubject subject, UUID studentId, Function<ChildAccess, T> action
	) {
		UUID parentId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentId);
		return databaseContext.withVerifiedChildScope(parentId, studentId,
			() -> action.apply(
				new ChildAccess(parentId, studentId, activeTeacherIds(studentId))));
	}

	/**
	 * {@code findTeachersOfChild} 는 인덱스를 타게 하려고 {@code student_id} 를 인자로 받는다.
	 * 🔴 격리는 인자가 아니라 V40 정책이 한다 — 범위 밖에서 부르면 예외 없이 0행이다.
	 */
	private Set<UUID> activeTeacherIds(UUID studentId) {
		List<TeacherSummaryView> teachers =
			rosterRelationships.findTeachersOfChild(studentId);
		Set<UUID> ids = new LinkedHashSet<>();
		for (TeacherSummaryView teacher : teachers) {
			ids.add(teacher.teacherId());
		}
		return ids;
	}

	/**
	 * 확인이 끝난 열람 범위.
	 *
	 * @param activeTeacherIds 자녀의 활성 강사. 🔴 비어 있으면 자녀의 어떤 강사 데이터도
	 *                         보이지 않는다 — 0 으로 채우거나 전체 허용으로 되돌리지 않는다
	 */
	public record ChildAccess(UUID parentId, UUID studentId, Set<UUID> activeTeacherIds) {

		/** 이 강사의 데이터를 학부모가 볼 수 있는가. 없으면 <b>404</b>다(403 아님). */
		public boolean allowsTeacher(UUID teacherId) {
			return teacherId != null && activeTeacherIds.contains(teacherId);
		}
	}
}
