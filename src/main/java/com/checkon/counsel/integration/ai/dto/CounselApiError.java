package com.checkon.counsel.integration.ai.dto;

import java.util.Map;

/** Shared {@code error} shape across all three counsel envelope responses. */
public record CounselApiError(
	String code,
	String message,
	Map<String, Object> detail
) {
}
