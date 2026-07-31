package com.checkon.account.application;

/**
 * 이메일 존재 여부와 비밀번호 오류를 구별하지 않는 공개 인증 실패다.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("invalid credentials");
	}
}
