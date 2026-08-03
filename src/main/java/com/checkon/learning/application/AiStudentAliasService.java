package com.checkon.learning.application;

import java.time.Clock;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkon.learning.infrastructure.persistence.AiStudentAliasRepository;

@Service
public class AiStudentAliasService {
	private static final int MAX_ATTEMPTS = 5;
	private final SecureRandom secureRandom = new SecureRandom();
	private final AiStudentAliasRepository repository;
	private final Clock clock;
	public AiStudentAliasService(AiStudentAliasRepository repository, Clock clock) {
		this.repository = repository; this.clock = clock;
	}

	public String getOrCreate(UUID teacherId, UUID studentId) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(studentId, "studentId must not be null");
		return repository.findByTeacherIdAndStudentId(teacherId, studentId)
			.map(alias -> alias.alias()).orElseGet(() -> create(teacherId, studentId));
	}

	private String create(UUID teacherId, UUID studentId) {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			byte[] bytes = new byte[16];
			secureRandom.nextBytes(bytes);
			String candidate = "st_" + HexFormat.of().formatHex(bytes);
			repository.insertIfAbsent(teacherId, studentId, candidate, Instant.now(clock));
			var stored = repository.findByTeacherIdAndStudentId(teacherId, studentId);
			if (stored.isPresent()) return stored.get().alias();
		}
		throw new IllegalStateException("AI student alias could not be allocated");
	}
}
