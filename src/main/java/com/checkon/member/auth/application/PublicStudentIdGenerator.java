package com.checkon.member.auth.application;

/**
 * 공개 학생 ID 후보를 만드는 포트.
 *
 * <p>🔴 {@code SecureRandom} 을 서비스 안에서 {@code new} 하지 않는다. 그러면 테스트가
 * 충돌을 재현할 수 없어 재시도 경로가 검증되지 않는다.</p>
 */
public interface PublicStudentIdGenerator {

	/** @return 정규화 전 후보 문자열. 형식 보정은 {@code PublicStudentId.normalize} 가 한다 */
	String nextCandidate();
}
