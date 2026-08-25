package com.checkon.member.auth.application;

/**
 * 가입 요청의 도메인 입력. 컨트롤러 DTO 와 분리해 검증 애노테이션이 서비스로 새지 않게 한다.
 *
 * @param grade 학생만 사용. 학부모는 {@code null}
 */
public record MemberSignUpCommand(
	String email,
	String password,
	String name,
	Integer grade
) {
}
