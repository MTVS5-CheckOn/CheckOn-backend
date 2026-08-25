package com.checkon.counsel.integration.ai;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionRequest;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionResponse;
import com.checkon.counsel.integration.ai.dto.GuardianLabelConfirmationRequest;
import com.checkon.counsel.integration.ai.dto.ConfirmationResponse;

final class HttpGuardianLabelClient implements GuardianLabelClient {

	static final String TENANT_ID_HEADER = "X-Tenant-Id";
	static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final RestClient client;
	private final String path;
	private final String confirmationsPath;

	HttpGuardianLabelClient(RestClient client, String path, String confirmationsPath) {
		this.client = client;
		this.path = requirePath(path, "labels suggest path");
		this.confirmationsPath = requirePath(confirmationsPath, "confirmations path");
	}

	@Override
	public ConfirmationResponse confirm(
		GuardianLabelConfirmationRequest request,
		String tenantAlias,
		String requestId
	) {
		return post(confirmationsPath, request, tenantAlias, requestId, ConfirmationResponse.class);
	}

	private <B, R> R post(String targetPath, B request, String tenantAlias, String requestId, Class<R> responseType) {
		try {
			R response = client.post().uri(targetPath)
				.header(TENANT_ID_HEADER, tenantAlias)
				.header(REQUEST_ID_HEADER, requestId)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.body(request).retrieve().body(responseType);
			if (response == null) throw GuardianLabelClientException.emptyResponse();
			return response;
		}
		catch (RestClientResponseException exception) { throw mapError(exception); }
		catch (GuardianLabelClientException exception) { throw exception; }
		catch (RestClientException exception) { throw GuardianLabelClientException.network(exception); }
	}

	@Override
	public GuardianLabelSuggestionResponse suggest(
		GuardianLabelSuggestionRequest request,
		String tenantAlias,
		String requestId
	) {
		try {
			var response = client.post()
				.uri(path)
				.header(TENANT_ID_HEADER, tenantAlias)
				.header(REQUEST_ID_HEADER, requestId)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.body(request)
				.retrieve()
				.body(GuardianLabelSuggestionResponse.class);
			if (response == null) throw GuardianLabelClientException.emptyResponse();
			validate(request, response);
			return response;
		}
		catch (RestClientResponseException exception) {
			throw mapError(exception);
		}
		catch (GuardianLabelClientException exception) {
			throw exception;
		}
		catch (RestClientException exception) {
			throw GuardianLabelClientException.network(exception);
		}
	}

	private static void validate(
		GuardianLabelSuggestionRequest request,
		GuardianLabelSuggestionResponse response
	) {
		if (response.data() == null || response.data().suggestions() == null) {
			throw GuardianLabelClientException.invalidResponse("guardian label AI response has no suggestions array");
		}
		var historyById = request.history().stream().collect(java.util.stream.Collectors.toMap(
			GuardianLabelSuggestionRequest.HistoryRecord::recordId,
			GuardianLabelSuggestionRequest.HistoryRecord::text,
			(first, second) -> first
		));
		var axes = java.util.EnumSet.noneOf(com.checkon.counsel.domain.GuardianLabelAxis.class);
		for (var suggestion : response.data().suggestions()) {
			if (suggestion == null || suggestion.suggestionId() == null || suggestion.suggestionId().isBlank()) {
				throw GuardianLabelClientException.invalidResponse("guardian label suggestion id is missing");
			}
			if (!request.guardianRef().equals(suggestion.guardianRef())) {
				throw GuardianLabelClientException.invalidResponse("guardian label suggestion echoes another guardian_ref");
			}
			if (suggestion.label() == null || !axes.add(suggestion.label().axis())) {
				throw GuardianLabelClientException.invalidResponse("guardian label response repeats an axis");
			}
			String expectedSuggestionId = request.guardianRef() + ":"
				+ suggestion.label().axis().wireValue() + ":" + suggestion.label().value().wireValue();
			if (!expectedSuggestionId.equals(suggestion.suggestionId())) {
				throw GuardianLabelClientException.invalidResponse("guardian label suggestion id does not match its label");
			}
			if (suggestion.evidenceQuotes() == null || suggestion.evidenceQuotes().isEmpty()) {
				throw GuardianLabelClientException.invalidResponse("guardian label suggestion has no evidence");
			}
			for (var evidence : suggestion.evidenceQuotes()) {
				String source = evidence == null ? null : historyById.get(evidence.recordId());
				if (source == null || evidence.quote() == null || evidence.quote().isBlank()
					|| !source.contains(evidence.quote())) {
					throw GuardianLabelClientException.invalidResponse("guardian label evidence is not present in request history");
				}
			}
		}
	}

	private static GuardianLabelClientException mapError(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		var reason = switch (status) {
			case 400 -> GuardianLabelClientException.Reason.INVALID_REQUEST;
			case 500 -> GuardianLabelClientException.Reason.INTERNAL_ERROR;
			case 503 -> GuardianLabelClientException.Reason.UPSTREAM_DOWN;
			case 504 -> GuardianLabelClientException.Reason.TIMEOUT;
			default -> GuardianLabelClientException.Reason.HTTP_ERROR;
		};
		return GuardianLabelClientException.http(reason, status, exception.getResponseBodyAsString(), exception);
	}

	private static String requirePath(String path, String name) {
		if (path == null || path.isBlank() || !path.startsWith("/")) {
			throw new IllegalArgumentException(name + " must start with '/'");
		}
		return path;
	}
}
