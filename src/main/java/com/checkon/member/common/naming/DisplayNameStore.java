package com.checkon.member.common.naming;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code member_display_names}(V39) 접근의 단일 출처.
 *
 * <p>🔴 이름의 유일한 원본이다. {@code student_profiles.alias} 는 강사가 관리하는 로스터 표시명이고
 * 여기와 별개다. 가입 시 alias 에 같은 값을 초기값으로 넣지만 그 뒤로는 동기화하지 않는다
 * (설계 정본 §1-4 ①).</p>
 *
 * <p>PR3 가입 트랜잭션이 이미 이 테이블에 INSERT 한다
 * ({@link com.checkon.member.auth.application.StudentSignUpService}).
 * PR6 는 프로필 API 가 이 저장소로 이름을 읽고, {@link #upsert} 는 후속 이름 변경 API 를 위해
 * 남겨 둔다(아직 계약에 없다).</p>
 */
public interface DisplayNameStore {

	Optional<String> read(UUID accountId);

	void upsert(UUID accountId, String displayName, Instant now);
}
