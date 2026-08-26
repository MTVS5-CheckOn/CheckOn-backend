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
}
