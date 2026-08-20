package com.checkon.counsel.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkon.counsel.infrastructure.persistence.AiGuardianAliasRepository;

/**
 * Issues the counsel contract's {@code parent_ref} — an opaque per-(teacher,
 * student) guardian channel, mirroring {@code AiStudentAliasService}. No
 * guardian identity (name, phone, relation) is modeled; that is out of scope
 * for the counsel AI contract, which only needs an opaque alias.
 */
@Service
public class AiGuardianAliasService {

	private static final int MAX_ATTEMPTS = 5;

	private final SecureRandom secureRandom = new SecureRandom();
	private final AiGuardianAliasRepository repository;
	private final Clock clock;

	public AiGuardianAliasService(AiGuardianAliasRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public String getOrCreate(UUID teacherId, UUID studentId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(studentId, "studentId must not be null");
		return repository.findAlias(teacherId, studentId).orElseGet(() -> create(teacherId, studentId));
	}

	private String create(UUID teacherId, UUID studentId) {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			byte[] bytes = new byte[16];
			secureRandom.nextBytes(bytes);
			String candidate = "pa_" + HexFormat.of().formatHex(bytes);
			repository.insertIfAbsent(teacherId, studentId, candidate, Instant.now(clock));
			var stored = repository.findAlias(teacherId, studentId);
			if (stored.isPresent()) return stored.get();
		}
		throw new IllegalStateException("AI guardian alias could not be allocated");
	}
}
