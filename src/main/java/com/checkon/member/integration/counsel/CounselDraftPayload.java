package com.checkon.member.integration.counsel;

public record CounselDraftPayload(
	String tenantRef,
	String studentRef,
	String parentRef,
	String classRef,
	String textMasked
) {
}
