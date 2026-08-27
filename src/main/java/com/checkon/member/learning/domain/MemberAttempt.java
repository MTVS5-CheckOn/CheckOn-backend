package com.checkon.member.learning.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code member_attempts} 한 행. 🔴 값 객체다 — 저장·수정은 리포지토리가 한다.
 *
 * <p>{@code version} 은 optimistic lock 이고 progress 요청의 {@code baseVersion} 과 비교한다.
 * {@code snapshotHash} 는 {@code sha256:} + 64 hex — {@code ck_member_attempts_hash} 가
 * 형식을 강제한다(V40:85-86).</p>
 */
public record MemberAttempt(
	UUID id,
	UUID studentId,
	UUID assignmentId,
	UUID teacherId,
	MemberAttemptStatus status,
	int version,
	String snapshotHash,
	int itemCount,
	int activeElapsedSec,
	Integer lastClientSequence,
	Instant startedAt,
	Instant lastProgressAt,
	Instant submittedAt,
	Instant scoredAt
) {
}
