package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.checkon.counsel.domain.GuardianLabelAxis;
import com.checkon.counsel.domain.GuardianLabelValue;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelSuggestionRepository.CachedSuggestions;
import com.checkon.counsel.infrastructure.persistence.GuardianLabelSuggestionRepository.StoredSuggestion;
import com.checkon.counsel.integration.ai.GuardianLabelClient;
import com.checkon.counsel.integration.ai.GuardianLabelClientException;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionRequest;
import com.checkon.counsel.integration.ai.dto.GuardianLabelSuggestionResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("학부모 라벨 제안 유스케이스")
class GuardianLabelSuggestionServiceTest {

	private static final UUID TEACHER = UUID.fromString("0198f900-0000-7000-8000-000000000901");
	private static final UUID PARENT = UUID.fromString("0198f900-0000-7000-8000-000000000902");

	@Mock
	private GuardianLabelSuggestionTransactions transactions;

	@Mock
	private GuardianLabelClient client;

	private GuardianLabelSuggestionService service;

	@BeforeEach
	void setUp() {
		service = new GuardianLabelSuggestionService(transactions, client);
	}

	@Nested
	@DisplayName("Given 유효한 상담 이력이 다섯 건보다 적을 때")
	class GivenInsufficientHistory {

		@Test
		@DisplayName("When 제안을 요청하면 Then AI를 호출하지 않고 대상 아님을 반환한다")
		void doesNotCallAi() {
			when(transactions.prepare(TEACHER, PARENT)).thenReturn(prepared(4, Optional.empty()));

			var result = service.suggest(TEACHER, PARENT);

			assertThat(result.eligible()).isFalse();
			assertThat(result.historyCount()).isEqualTo(4);
			assertThat(result.suggestions()).isEmpty();
			verifyNoInteractions(client);
		}
	}

	@Nested
	@DisplayName("Given 같은 이력 버전의 저장된 결과가 있을 때")
	class GivenCachedHistoryVersion {

		@Test
		@DisplayName("When 다시 요청하면 Then AI를 호출하지 않고 빈 제안 결과도 캐시로 반환한다")
		void returnsCachedEmptySuggestions() {
			var cached = new CachedSuggestions(UUID.randomUUID(), "gd_cached", "exec-1", List.of());
			when(transactions.prepare(TEACHER, PARENT)).thenReturn(prepared(5, Optional.of(cached)));

			var result = service.suggest(TEACHER, PARENT);

			assertThat(result.eligible()).isTrue();
			assertThat(result.cacheHit()).isTrue();
			assertThat(result.suggestions()).isEmpty();
			verifyNoInteractions(client);
		}
	}

	@Nested
	@DisplayName("Given 처음 보는 이력 버전일 때")
	class GivenNewHistoryVersion {

		@Test
		@DisplayName("When AI가 제안하면 Then DB 조회 트랜잭션 밖에서 호출한 뒤 별도 저장 경계로 넘긴다")
		void callsAiAndStoresTheResponse() {
			var prepared = prepared(5, Optional.empty());
			var response = response();
			var stored = new CachedSuggestions(UUID.randomUUID(), "gd_parent", "exec-2", List.of(
				new StoredSuggestion("gd_parent:comm:data", GuardianLabelAxis.COMM, GuardianLabelValue.DATA,
					new BigDecimal("0.86"), "[]")
			));
			when(transactions.prepare(TEACHER, PARENT)).thenReturn(prepared);
			when(client.suggest(any(), eq("tn_teacher"), any())).thenReturn(response);
			when(transactions.store(TEACHER, prepared, response)).thenReturn(stored);

			var result = service.suggest(TEACHER, PARENT);

			assertThat(result.cacheHit()).isFalse();
			assertThat(result.suggestions()).extracting(GuardianLabelSuggestionService.SuggestionView::value)
				.containsExactly(GuardianLabelValue.DATA);
			verify(client).suggest(any(), eq("tn_teacher"), any());
			verify(transactions).store(TEACHER, prepared, response);
		}

		@Test
		@DisplayName("When AI 호출이 실패하면 Then 자동 재시도나 결과 저장 없이 오류를 노출한다")
		void doesNotRetryOrStoreAnAiFailure() {
			var prepared = prepared(5, Optional.empty());
			when(transactions.prepare(TEACHER, PARENT)).thenReturn(prepared);
			when(client.suggest(any(), eq("tn_teacher"), any()))
				.thenThrow(GuardianLabelClientException.network(new RuntimeException("down")));

			assertThatThrownBy(() -> service.suggest(TEACHER, PARENT))
				.isInstanceOf(GuardianLabelSuggestionException.class)
				.satisfies(exception -> assertThat(((GuardianLabelSuggestionException) exception).reason())
					.isEqualTo(GuardianLabelSuggestionException.Reason.UPSTREAM_FAILURE));
			verify(client).suggest(any(), eq("tn_teacher"), any());
			verify(transactions, never()).store(any(), any(), any());
		}

		@Test
		@DisplayName("When AI가 모든 이력을 차단하면 Then 재시도 없이 분석 불가 상태로 변환한다")
		void mapsAllBlockedHistoryWithoutRetry() {
			var prepared = prepared(5, Optional.empty());
			when(transactions.prepare(TEACHER, PARENT)).thenReturn(prepared);
			when(client.suggest(any(), eq("tn_teacher"), any()))
				.thenThrow(GuardianLabelClientException.historyAllBlocked());

			var result = service.suggest(TEACHER, PARENT);

			assertThat(result.eligible()).isFalse();
			assertThat(result.reason()).isEqualTo(
				GuardianLabelSuggestionService.EligibilityReason.ANALYSIS_UNAVAILABLE
			);
			verify(client).suggest(any(), eq("tn_teacher"), any());
			verify(transactions, never()).store(any(), any(), any());
		}
	}

	private GuardianLabelSuggestionTransactions.PreparedRequest prepared(
		int historyCount,
		Optional<CachedSuggestions> cached
	) {
		var history = java.util.stream.IntStream.range(0, historyCount)
			.mapToObj(index -> new GuardianLabelSuggestionRequest.HistoryRecord(
				"record-" + index,
				GuardianLabelSuggestionRequest.Direction.inbound,
				"본문 " + index,
				OffsetDateTime.parse("2026-08-25T10:00:00+09:00").plusMinutes(index)
			)).toList();
		return new GuardianLabelSuggestionTransactions.PreparedRequest(
			PARENT, "gd_parent", "tn_teacher", history, cached
		);
	}

	private GuardianLabelSuggestionResponse response() {
		return new GuardianLabelSuggestionResponse(
			new GuardianLabelSuggestionResponse.Data(List.of(new GuardianLabelSuggestionResponse.Suggestion(
				"gd_parent:comm:data", "gd_parent",
				new GuardianLabelSuggestionResponse.Label(GuardianLabelAxis.COMM, GuardianLabelValue.DATA),
				new BigDecimal("0.86"),
				List.of(new GuardianLabelSuggestionResponse.EvidenceQuote("record-0", "본문"))
			))), null, null
		);
	}
}
