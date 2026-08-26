package com.checkon.member.integration.roster.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 학부모↔자녀 활성 관계 한 건과 그 학생의 기본 정보.
 *
 * @param linkedAt {@code parent_student_relationships.started_at}
 */
public record ChildLinkView(
	UUID studentProfileId,
	UUID accountId,
	String alias,
	Integer grade,
	String publicId,
	Instant linkedAt
) {
}
