package com.checkon.account.application;

public class InvalidSessionException extends RuntimeException {

	public InvalidSessionException() {
		super("session is invalid");
	}
}
