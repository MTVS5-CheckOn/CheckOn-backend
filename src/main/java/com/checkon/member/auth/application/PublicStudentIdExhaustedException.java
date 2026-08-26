package com.checkon.member.auth.application;

/**
 * 공개 학생 ID 를 상한 안에 발급하지 못했다.
 *
 * <p>🔴 가입 트랜잭션 전체가 롤백돼야 한다 — 계정만 남고 공개 ID 가 없으면
 * 학부모가 자녀를 찾을 방법이 사라진다.</p>
 */
public class PublicStudentIdExhaustedException extends RuntimeException {

	public PublicStudentIdExhaustedException(int attempts) {
		super("could not issue a unique public student id within " + attempts + " attempts");
	}
}
