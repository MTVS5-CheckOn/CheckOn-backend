package com.checkon.member.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.checkon.member.analytics.application.dto.AnalysisResponse;
import com.checkon.member.analytics.application.dto.AnalysisResponse.Improvement;
import com.checkon.member.analytics.application.dto.AnalysisResponse.Overall;
import com.checkon.member.analytics.application.dto.AnalysisResponse.WeaknessCellPayload;
import com.checkon.member.analytics.domain.WeaknessImprovement;
import com.checkon.member.analytics.domain.WeaknessImprovement.Cell;
import com.checkon.member.analytics.domain.WeaknessImprovement.Status;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 분석 응답 JSON 계약 — PR7 지시서 §5 테스트 17·18·19.
 *
 * <p>🔴 <b>DTO 객체가 아니라 wire JSON 문자열</b>을 본다. {@code doesNotExist()} 로 단언하지 않는다
 * (지시서 §3 「응답」). 대신 원문 JSON 을 문자열 검색한다.</p>
 */
class AnalysisContractTest {

	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

	@Test
	@DisplayName("#17 응답 raw JSON 에 전국 백분위 키·문자열이 존재하지 않는다")
	void noNationalPercentileKey() throws Exception {
		AnalysisResponse response = new AnalysisResponse(
			"2026-08", "mm-1", Instant.parse("2026-08-27T00:00:00Z"),
			new Overall("AVAILABLE", new BigDecimal("0.51"), 100, 42),
			List.of(fakeCell()),
			fakeCell());

		String json = MAPPER.writeValueAsString(response);

		assertThat(json)
			.as("전국 백분위 필드는 존재하지 않는다 (CheckOn-AI unproduced)")
			.doesNotContain("percentile")
			.doesNotContain("national");
	}

	@Test
	@DisplayName("#18 응답에 minimumSampleSize 가 설정값(=7)으로 되돌아온다")
	void minimumSampleSizeIsEchoed() throws Exception {
		WeaknessImprovement improved = WeaknessImprovement.of(
			new Cell(20, 10), new Cell(20, 8), 7);
		Improvement improvement = new Improvement(
			improved.status().name(),
			improved.previousAccuracyRate(),
			improved.accuracyDeltaPp(),
			improved.minimumSampleSize());
		WeaknessCellPayload cell = new WeaknessCellPayload(
			UUID.randomUUID(), "reading", "fact", "AVAILABLE",
			20, 10, new BigDecimal("0.5"), improvement);

		String json = MAPPER.writeValueAsString(cell);

		assertThat(json)
			.contains("\"minimumSampleSize\":7")
			.doesNotContain("\"minimumSampleSize\":10");
	}

	@Test
	@DisplayName("#19 INSUFFICIENT_SAMPLE 응답의 accuracyDeltaPp 는 JSON null 이다")
	void insufficientDeltaIsNull() throws Exception {
		WeaknessImprovement improved = WeaknessImprovement.of(
			new Cell(5, 3), new Cell(20, 10), 10);
		assertThat(improved.status()).isEqualTo(Status.INSUFFICIENT_SAMPLE);
		Improvement improvement = new Improvement(
			improved.status().name(),
			improved.previousAccuracyRate(),
			improved.accuracyDeltaPp(),
			improved.minimumSampleSize());
		WeaknessCellPayload cell = new WeaknessCellPayload(
			UUID.randomUUID(), "reading", "fact", "AVAILABLE",
			5, 3, new BigDecimal("0.6"), improvement);

		String json = MAPPER.writeValueAsString(cell);

		assertThat(json)
			.as("null 이 아니라 0.0 이면 「변화 없음」 이라는 거짓말이 된다")
			.contains("\"accuracyDeltaPp\":null");
	}

	private static WeaknessCellPayload fakeCell() {
		return new WeaknessCellPayload(
			UUID.randomUUID(), "reading", "fact", "AVAILABLE",
			20, 10, new BigDecimal("0.5"),
			new Improvement("AVAILABLE", new BigDecimal("0.4"),
				new BigDecimal("10.0"), 10));
	}
}
