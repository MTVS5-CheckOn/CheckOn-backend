package com.checkon.member.auth.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.checkon.member.auth.application.DisplayNameQueryPort;

/** {@code member_display_names} 읽기 포트 구현. 소비자는 Repository 를 직접 보지 않는다. */
@Component
public class DisplayNameQueryAdapter implements DisplayNameQueryPort {

	private final MemberDisplayNameRepository displayNameRepository;

	public DisplayNameQueryAdapter(MemberDisplayNameRepository displayNameRepository) {
		this.displayNameRepository = displayNameRepository;
	}

	@Override
	public Optional<String> findDisplayName(UUID accountId) {
		return displayNameRepository.find(accountId);
	}
}
