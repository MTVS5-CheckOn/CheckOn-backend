package com.checkon.counsel.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkon.counsel.infrastructure.persistence.AiParentLabelAliasRepository;

@Service
public class AiParentLabelAliasService {

	private static final int MAX_ATTEMPTS = 5;

	private final SecureRandom random = new SecureRandom();
	private final AiParentLabelAliasRepository repository;
	private final Clock clock;

	public AiParentLabelAliasService(AiParentLabelAliasRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public String getOrCreate(UUID teacherId, UUID parentId) {
		return repository.findAlias(teacherId, parentId).orElseGet(() -> create(teacherId, parentId));
	}

	private String create(UUID teacherId, UUID parentId) {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			byte[] bytes = new byte[16];
			random.nextBytes(bytes);
			String candidate = "gd_" + HexFormat.of().formatHex(bytes);
			repository.insertIfAbsent(teacherId, parentId, candidate, Instant.now(clock));
			var stored = repository.findAlias(teacherId, parentId);
			if (stored.isPresent()) return stored.get();
		}
		throw new IllegalStateException("AI parent label alias could not be allocated");
	}
}
