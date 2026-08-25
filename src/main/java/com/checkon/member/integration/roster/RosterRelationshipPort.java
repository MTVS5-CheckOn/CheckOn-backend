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

	/**
	 * 🔴 <b>학부모 컨텍스트에서는 쓸 수 없다.</b> {@code teacher_student_relationships} 에
	 * 학부모용 SELECT 정책이 없다(V38:126-135 — 불변식 4번 재귀 때문에 의도적으로 뺐다).
	 * 학생 본인 컨텍스트에서만 행이 보인다.
	 */
	List<TeacherSummaryView> findTeachersOfStudent(UUID studentProfileId);

	Optional<TeacherSummaryView> findTeacherSummary(UUID teacherId);

	/**
	 * 🔴 호출자 자신의 연결만 보인다. 다른 학부모의 연결은 정책이 가린다 —
	 * 「연결 안 됨」이 아니라 「내 눈에 안 보임」이다. 최종 판정은 등록 시점의 unique index 다.
	 */
	boolean existsActiveParentLink(UUID studentProfileId);
}
