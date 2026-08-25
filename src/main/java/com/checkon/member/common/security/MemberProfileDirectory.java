package com.checkon.member.common.security;

import java.util.Optional;
import java.util.UUID;

/**
 * 계정 식별자로 학생·학부모 프로필 식별자를 찾는 포트.
 *
 * <p>{@code AuthenticatedAccount} 에는 {@code teacherProfileId} 밖에 없어서 member 가 직접 푼다.
 * 구현은 {@code member/integration/account} 에 둔다.</p>
 */
public interface MemberProfileDirectory {

	Optional<UUID> findStudentProfileId(UUID accountId);

	Optional<UUID> findParentProfileId(UUID accountId);
}
