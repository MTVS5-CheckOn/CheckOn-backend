package com.checkon.account.application;

public class AccountNotActiveException extends RuntimeException {

	public AccountNotActiveException() {
		super("account is not active");
	}
}
