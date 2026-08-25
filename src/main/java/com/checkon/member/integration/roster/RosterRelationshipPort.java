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
 * <p>🔴 <b>{@code findTeachersOfStudent} 를 여기 두지 않는다.</b> 지시서는 최소 포트 메서드로
 * 열거했지만, 유일한 소비자였던 {@code Child.teachers} 가 MB-36 으로 사라졌다. 호출자도 테스트도
 * 없는 채로 남겨 두면 <b>학부모 컨텍스트에서 불러 조용히 빈 배열을 받는</b> 경로가 된다 —
 * PR3 결함 3(「teachers 가 조용히 빈 배열 — 200 이라 더 조용하다」)과 같은 모양이다.
 * 학생 본인 컨텍스트에서 필요해지면 <b>호출자·테스트와 같은 커밋에서</b> 되살린다.</p>
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
}
