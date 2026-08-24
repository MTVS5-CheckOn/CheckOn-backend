package com.checkon.counsel.integration.ai;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.checkon.counsel.integration.ai.dto.ClassifyRequest;
import com.checkon.counsel.integration.ai.dto.ClassifyResponse;
import com.checkon.counsel.integration.ai.dto.ConfirmationRequest;
import com.checkon.counsel.integration.ai.dto.ConfirmationResponse;

final class HttpClassifyClient implements ClassifyClient {

	static final String TENANT_ID_HEADER = "X-Tenant-Id";
	static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final RestClient restClient;
	private final String classifyPath;
	private final String confirmationsPath;

	HttpClassifyClient(RestClient restClient, String classifyPath, String confirmationsPath) {
		this.restClient = restClient;
		this.classifyPath = requirePath(classifyPath, "classifyPath");
		this.confirmationsPath = requirePath(confirmationsPath, "confirmationsPath");
	}

	@Override
	public ClassifyResponse classify(ClassifyRequest request, String tenantAlias, String requestId) {
		return post(classifyPath, request, tenantAlias, requestId, ClassifyResponse.class);
	}

	@Override
	public ConfirmationResponse confirm(ConfirmationRequest request, String tenantAlias, String requestId) {
		return post(confirmationsPath, request, tenantAlias, requestId, ConfirmationResponse.class);
	}

	private <B, R> R post(String path, B body, String tenantAlias, String requestId, Class<R> responseType) {
		try {
			R response = restClient.post()
				.uri(path)
				.header(TENANT_ID_HEADER, tenantAlias)
				.header(REQUEST_ID_HEADER, requestId)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.body(body)
				.retrieve()
				.body(responseType);
			if (response == null) throw ClassifyClientException.emptyResponse();
			return response;
		}
		catch (RestClientResponseException exception) { throw mapError(exception); }
		catch (ClassifyClientException exception) { throw exception; }
		catch (RestClientException exception) { throw ClassifyClientException.networkError(exception); }
	}

	private static ClassifyClientException mapError(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		String body = exception.getResponseBodyAsString();
		return switch (status) {
			case 400 -> ClassifyClientException.invalidRequest(body, exception);
			case 404 -> ClassifyClientException.notFound(body, exception);
			case 503 -> ClassifyClientException.upstreamDown(body, exception);
			case 504 -> ClassifyClientException.timeout(body, exception);
			case 500 -> ClassifyClientException.internalError(body, exception);
			default -> ClassifyClientException.httpError(status, body, exception);
		};
	}

	private static String requirePath(String path, String name) {
		if (path == null || path.isBlank() || !path.startsWith("/")) {
			throw new IllegalArgumentException(name + " must start with '/'");
		}
		return path;
	}
}
