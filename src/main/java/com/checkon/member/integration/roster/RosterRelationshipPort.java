package com.checkon.member.integration.roster;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.checkon.member.integration.roster.dto.ChildLinkView;
import com.checkon.member.integration.roster.dto.StudentIdentity;
import com.checkon.member.integration.roster.dto.TeacherSummaryView;

/**
 * roster 소유 테이블을 <b>읽기 전용</b>으로 여는 출력 포트.
 *
 * <p>관계 INSERT 는 여기 없다 — membership 이 자기 writer 로 한다. 조회와 쓰기를 한 인터페이스에
 * 두면 "읽기 어댑터"라는 경계가 흐려진다.</p>
 *
 * <p>🔴 {@link #findTeachersOfChild} 는 학부모 컨텍스트로 <b>범위가 열린 뒤에만</b> 부른다.
 * V40 의 {@code teacher_student_relationships_member_parent_scope_select} 정책이
 * {@code current_checkon_parent_id()} + {@code current_checkon_scope_student_id()} 를 요구하고,
 * 범위는 {@code MemberDatabaseContext.withVerifiedChildScope} 가 여닫는다. 그 안에서
 * 부르지 않으면 조용히 <b>빈 배열</b>이 나온다(설계 §6-4-3).</p>
 */
public interface RosterRelationshipPort {

	/**
	 * 🔴 {@code member_student_public_ids} 는 RLS 밖이다(MB-30). 행 보호가 DB 가 아니라
	 * <b>애플리케이션 책임</b>이므로 이 메서드는 레이트 리미터를 통과한 호출자만 쓴다.
	 *
	 * @param normalizedPublicId {@code PublicStudentId.normalize} 를 통과한 값
	 */
	Optional<StudentIdentity> findStudentByPublicId(String normalizedPublicId);

	/** 활성 자녀 목록. 정책이 {@code parent_id} 로 이미 격리한다. */
	List<ChildLinkView> findActiveChildren(UUID parentProfileId);

	Optional<TeacherSummaryView> findTeacherSummary(UUID teacherId);

	/**
	 * 🔴 호출자 자신의 연결만 보인다. 다른 학부모의 연결은 정책이 가린다 —
	 * 「연결 안 됨」이 아니라 「내 눈에 안 보임」이다. 최종 판정은 등록 시점의 unique index 다.
	 */
	boolean existsActiveParentLink(UUID studentProfileId);

	/**
	 * 자녀의 활성 강사 목록. 🔴 <b>{@code withVerifiedChildScope} 안에서 호출한다</b> —
	 * 밖에서 부르면 V40 정책이 술어에서 걸러 <b>0행</b>이 나온다(예외 아님).
	 * PAUSED 관계는 강사가 아닌 학생·학부모 화면에 보일 이유가 없어 ACTIVE 만 반환한다.
	 */
	List<TeacherSummaryView> findTeachersOfChild(UUID studentProfileId);

	/**
	 * 학부모 자신과 활성 관계인 강사 id 전량.
	 *
	 * <p>🔴 <b>범위 세션 변수가 필요 없다</b> — {@code parent_teacher_relationships} 의
	 * V38 정책이 {@code parent_id = current_checkon_parent_id()} 로 이미 격리한다.
	 * {@link #findTeachersOfChild} 와 달리 {@code withVerifiedChildScope} 밖에서도 돈다.</p>
	 *
	 * <p>🔴 설계 §9-2 「{@code teacherId} 는 권한이 아니라 필터다. 서버가 {@code parent↔teacher}
	 * 와 {@code student↔teacher} 관계 교집합을 매 요청 재검증한다」의 <b>앞쪽 절반</b>이다.
	 * 뒤쪽 절반은 {@link #findTeachersOfChild} 가 낸다.</p>
	 *
	 * <p>🔴 {@code member/auth} 의 {@code MemberTeacherRepository#findForParent} 가 <b>같은
	 * 테이블</b>을 읽는다. 그쪽은 세션 응답에 실을 표시 이름까지 가져오고 여기는 id 만 본다 —
	 * 목적이 달라 합치지 않았지만 <b>한 테이블에 두 조회기</b>라는 위험은 남는다(MB-58).</p>
	 */
	List<UUID> findActiveTeacherIdsOfParent(UUID parentProfileId);
}
