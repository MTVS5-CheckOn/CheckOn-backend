package com.checkon.detection.integration.ai;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;

public class HttpRiskDetectionClient implements RiskDetectionClient {

	static final String TENANT_ID_HEADER = "X-Tenant-Id";
	static final String REQUEST_ID_HEADER = "X-Request-Id";
	static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final RestClient restClient;

	public HttpRiskDetectionClient(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public AiDetectionResponse detect(
		AiDetectionRequest request,
		AiDetectionRequestHeaders headers
	) {
		try {
			AiDetectionResponse response = restClient.post()
				.uri("/v1/detect")
				.header(TENANT_ID_HEADER, headers.tenantId())
				.header(REQUEST_ID_HEADER, headers.requestId())
				.header(IDEMPOTENCY_KEY_HEADER, headers.idempotencyKey().value())
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.body(request)
				.retrieve()
				.body(AiDetectionResponse.class);

			if (response == null) {
				throw RiskDetectionClientException.emptyResponse();
			}
			return response;
		}
		catch (RestClientResponseException exception) {
			int status = exception.getStatusCode().value();
			if (status == 409) {
				throw RiskDetectionClientException.idempotencyConflict(exception);
			}
			throw RiskDetectionClientException.httpError(status, exception);
		}
		catch (RestClientException exception) {
			throw RiskDetectionClientException.networkError(exception);
		}
	}
}
