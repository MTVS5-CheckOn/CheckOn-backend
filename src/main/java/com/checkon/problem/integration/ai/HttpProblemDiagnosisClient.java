package com.checkon.problem.integration.ai;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

final class HttpProblemDiagnosisClient implements ProblemDiagnosisClient {
	private final RestClient client;
	private final String path;

	HttpProblemDiagnosisClient(RestClient client, String path) {
		this.client = client;
		if (path == null || path.isBlank() || !path.startsWith("/"))
			throw new IllegalArgumentException("diagnosis path must start with '/'");
		this.path = path;
	}

	@Override
	public String diagnose(String requestPayload, Headers headers) {
		try {
			String body = client.post().uri(path)
				.header("X-Tenant-Id", headers.tenantAlias())
				.header("X-Request-Id", headers.requestId())
				.header("Idempotency-Key", headers.idempotencyKey())
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.body(requestPayload.getBytes(StandardCharsets.UTF_8))
				.retrieve().body(String.class);
			if (body == null || body.isBlank()) throw ProblemDiagnosisClientException.network(null);
			return body;
		}
		catch (RestClientResponseException exception) {
			throw ProblemDiagnosisClientException.http(exception.getStatusCode().value(), exception);
		}
		catch (ProblemDiagnosisClientException exception) { throw exception; }
		catch (RestClientException exception) { throw ProblemDiagnosisClientException.network(exception); }
	}
}
