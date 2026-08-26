package com.checkon.member.auth.application;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.checkon.member.auth.domain.PublicStudentId;
import com.checkon.member.auth.infrastructure.persistence.PublicStudentIdRepository;

/**
 * 공개 학생 ID 를 발급한다.
 *
 * <p>🔴 상한을 넘기면 가입 트랜잭션 전체를 롤백시킨다. 공개 ID 없이 계정만 남기면
 * 학부모가 영원히 자녀를 못 찾는다.</p>
 *
 * <p>로그에는 시도 횟수만 남긴다 — 후보 문자열을 남기면 그 자체가 열거 단서가 된다.</p>
 */
@Component
public class PublicStudentIdIssuer {

	private static final Logger log = LoggerFactory.getLogger(PublicStudentIdIssuer.class);

	private final PublicStudentIdGenerator generator;
	private final PublicStudentIdRepository repository;
	private final MemberAuthProperties properties;

	public PublicStudentIdIssuer(
		PublicStudentIdGenerator generator,
		PublicStudentIdRepository repository,
		MemberAuthProperties properties
	) {
		this.generator = generator;
		this.repository = repository;
		this.properties = properties;
	}

	public String issue(UUID studentProfileId, Instant now) {
		int maxAttempts = properties.publicIdMaxAttempts();
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			String candidate = PublicStudentId.normalize(generator.nextCandidate());
			if (candidate == null) {
				continue;
			}
			try {
				repository.insert(studentProfileId, candidate, now);
				return candidate;
			}
			catch (DataIntegrityViolationException collision) {
				// uq_member_student_public_ids_public 충돌. 다음 후보로 넘어간다.
				log.debug("public student id collision on attempt {}", attempt);
			}
		}
		throw new PublicStudentIdExhaustedException(maxAttempts);
	}
}
