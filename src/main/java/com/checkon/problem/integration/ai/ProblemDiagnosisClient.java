package com.checkon.problem.integration.ai;

public interface ProblemDiagnosisClient {
	String diagnose(String requestPayload, Headers headers);

	record Headers(String tenantAlias, String requestId, String idempotencyKey) { }
}
