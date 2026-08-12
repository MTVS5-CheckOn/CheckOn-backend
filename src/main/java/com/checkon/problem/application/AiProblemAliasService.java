package com.checkon.problem.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.checkon.problem.infrastructure.persistence.AiProblemAliasRepository;

@Service
public class AiProblemAliasService {
	private static final int MAX_ATTEMPTS = 5;
	private final SecureRandom secureRandom = new SecureRandom();
	private final AiProblemAliasRepository repository;
	private final Clock clock;

	public AiProblemAliasService(AiProblemAliasRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public String getOrCreateTenantAlias(UUID teacherId) {
		return getOrCreate("tn_", () -> repository.findTenantAlias(teacherId).orElse(null),
			alias -> repository.insertTenantAliasIfAbsent(teacherId, alias, Instant.now(clock)));
	}

	public String getOrCreateClassAlias(UUID teacherId, UUID classGroupId) {
		return getOrCreate("cl_", () -> repository.findClassAlias(teacherId, classGroupId).orElse(null),
			alias -> repository.insertClassAliasIfAbsent(teacherId, classGroupId, alias, Instant.now(clock)));
	}

	private String getOrCreate(String prefix, Supplier<String> existing, Consumer<String> insert) {
		String found = existing.get();
		if (found != null) return found;
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			byte[] bytes = new byte[16];
			secureRandom.nextBytes(bytes);
			insert.accept(prefix + HexFormat.of().formatHex(bytes));
			found = existing.get();
			if (found != null) return found;
		}
		throw new IllegalStateException(prefix + " AI alias could not be allocated");
	}
}
