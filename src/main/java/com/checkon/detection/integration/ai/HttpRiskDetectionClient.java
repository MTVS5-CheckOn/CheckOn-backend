package com.checkon.detection.integration.ai;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public class HttpRiskDetectionClient implements RiskDetectionClient {

	static final String TENANT_ID_HEADER = "X-Tenant-Id";
	static final String REQUEST_ID_HEADER = "X-Request-Id";
	static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final RestClient restClient;
	private final String detectPath;
	private final ObjectMapper objectMapper;

	public HttpRiskDetectionClient(
		RestClient restClient,
		String detectPath,
		ObjectMapper objectMapper
	) {
		this.restClient = restClient;
		this.objectMapper = objectMapper;
		if (detectPath == null
			|| detectPath.isBlank()
			|| !detectPath.startsWith("/")) {
			throw new IllegalArgumentException(
				"detectPath must start with '/'"
			);
		}
		this.detectPath = detectPath;
	}

	@Override
	public AiDetectionResponse detect(
		AiDetectionRequest request,
		AiDetectionRequestHeaders headers
	) {
		return detectRaw(writeRequest(request), headers);
	}

	@Override
	public AiDetectionResponse detectRaw(
		String requestBody,
		AiDetectionRequestHeaders headers
	) {
		try {
			AiDetectionResponse response = restClient.post()
				.uri(detectPath)
				.header(TENANT_ID_HEADER, headers.tenantId())
				.header(REQUEST_ID_HEADER, headers.requestId())
				.header(IDEMPOTENCY_KEY_HEADER, headers.idempotencyKey().value())
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				// ByteArrayHttpMessageConverter sends the validated Kafka payload
				// verbatim. A DTO conversion here can change timestamp/null rendering
				// and make the transmitted body disagree with snapshot_hash.
				.body(requestBody.getBytes(StandardCharsets.UTF_8))
				.retrieve()
				.body(AiDetectionResponse.class);

			if (response == null) {
				throw RiskDetectionClientException.emptyResponse();
			}
			return response;
		}
		catch (RestClientResponseException exception) {
			int status = exception.getStatusCode().value();
			String responseBody = exception.getResponseBodyAsString();
			if (status == 409) {
				throw RiskDetectionClientException.idempotencyConflict(
					responseBody,
					exception
				);
			}
			throw RiskDetectionClientException.httpError(
				status,
				responseBody,
				exception
			);
		}
		catch (RestClientException exception) {
			throw RiskDetectionClientException.networkError(exception);
		}
	}

	private String writeRequest(AiDetectionRequest request) {
		try {
			return objectMapper.writeValueAsString(request);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException(
				"AI detection request could not be serialized",
				exception
			);
		}
	}
}
