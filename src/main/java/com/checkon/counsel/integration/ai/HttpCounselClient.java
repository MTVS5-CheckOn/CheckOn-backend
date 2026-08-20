package com.checkon.counsel.integration.ai;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.checkon.counsel.integration.ai.dto.CounselDraftGetResponse;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineRequest;
import com.checkon.counsel.integration.ai.dto.CounselDraftRefineResponse;

/**
 * GET and refine now run on separate {@link RestClient}s with different read
 * timeouts (AI-A 2026-08-20 타임아웃권고 실측) -- GET never runs a job so it
 * only needs a few seconds, but refine is still a synchronous LLM call and
 * must keep its old generous budget. A single {@code JdkClientHttpRequestFactory}
 * only supports one read timeout, so two are wired up rather than one.
 */
final class HttpCounselClient implements CounselClient {

	static final String TENANT_ID_HEADER = "X-Tenant-Id";
	static final String REQUEST_ID_HEADER = "X-Request-Id";
	static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final RestClient getRestClient;
	private final RestClient refineRestClient;
	private final String draftsPath;

	HttpCounselClient(RestClient getRestClient, RestClient refineRestClient, String draftsPath) {
		this.getRestClient = getRestClient;
		this.refineRestClient = refineRestClient;
		if (draftsPath == null || draftsPath.isBlank() || !draftsPath.startsWith("/")) {
			throw new IllegalArgumentException("draftsPath must start with '/'");
		}
		this.draftsPath = draftsPath.endsWith("/")
			? draftsPath.substring(0, draftsPath.length() - 1)
			: draftsPath;
	}

	@Override
	public CounselDraftGetResponse getDraft(String jobId, String tenantAlias, String requestId) {
		try {
			RestClient.RequestHeadersSpec<?> spec = getRestClient.get()
				.uri(draftsPath + "/{jobId}", jobId)
				.header(TENANT_ID_HEADER, tenantAlias);
			if (requestId != null) spec = spec.header(REQUEST_ID_HEADER, requestId);
			CounselDraftGetResponse response = spec.retrieve().body(CounselDraftGetResponse.class);
			if (response == null) throw CounselClientException.emptyResponse();
			return response;
		}
		catch (RestClientResponseException exception) { throw mapError(exception); }
		catch (CounselClientException exception) { throw exception; }
		catch (RestClientException exception) { throw CounselClientException.networkError(exception); }
	}

	@Override
	public CounselDraftRefineResponse refineDraft(String jobId, CounselDraftRefineRequest request, RequestHeaders headers) {
		try {
			RestClient.RequestBodySpec spec = refineRestClient.post()
				.uri(draftsPath + "/{jobId}/refine", jobId)
				.header(TENANT_ID_HEADER, headers.tenantAlias())
				.header(IDEMPOTENCY_KEY_HEADER, headers.idempotencyKey())
				.header(HttpHeaders.CONTENT_TYPE, "application/json");
			if (headers.requestId() != null) spec = spec.header(REQUEST_ID_HEADER, headers.requestId());
			CounselDraftRefineResponse response = spec.body(request)
				.retrieve()
				.body(CounselDraftRefineResponse.class);
			if (response == null) throw CounselClientException.emptyResponse();
			return response;
		}
		catch (RestClientResponseException exception) { throw mapError(exception); }
		catch (CounselClientException exception) { throw exception; }
		catch (RestClientException exception) { throw CounselClientException.networkError(exception); }
	}

	private static CounselClientException mapError(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		String body = exception.getResponseBodyAsString();
		if (status == 409) return CounselClientException.idempotencyConflict(body, exception);
		if (status == 404) return CounselClientException.notFound(body, exception);
		return CounselClientException.httpError(status, body, exception);
	}
}
