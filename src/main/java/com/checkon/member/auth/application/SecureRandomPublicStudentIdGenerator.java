package com.checkon.member.auth.application;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/** 기본 구현. 혼동하기 쉬운 글자(I·O·0·1)를 뺀 32자 알파벳에서 6자를 뽑는다. */
@Component
public class SecureRandomPublicStudentIdGenerator implements PublicStudentIdGenerator {

	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int LENGTH = 6;

	private final SecureRandom random = new SecureRandom();

	@Override
	public String nextCandidate() {
		StringBuilder builder = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return "STU-" + builder;
	}
}
