package com.checkon.problem.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class ProblemGenerationPayloadHasher {
	public String sha256(String payload) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(payload.getBytes(StandardCharsets.UTF_8));
			return "sha256:" + HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
