package com.checkon.member.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.checkon.member.analytics.domain.WeaknessImprovement;
import com.checkon.member.analytics.domain.WeaknessImprovement.Cell;
import com.checkon.member.analytics.domain.WeaknessImprovement.Status;

/**
 * 개선도 산식 골든 케이스 — PR7 지시서 §5.
 *
 * <p>🔴 최소 표본 10 고정. 반올림·경계·부호·null 처리를 모두 검사한다.</p>
 */
final class WeaknessImprovementTest {

	private static final int MIN = 10;

	static Stream<Arguments> goldens() {
		return Stream.of(
			// # | cur (correct/scored) | prev | status | deltaPp
			Arguments.of("1-정본 §10-2 예시 · 8pp", cell(51, 100), cell(43, 100),
				Status.AVAILABLE, "8.0"),
			Arguments.of("2-부호", cell(43, 100), cell(51, 100),
				Status.AVAILABLE, "-8.0"),
			Arguments.of("3-경계 표본 = min", cell(6, 10), cell(5, 10),
				Status.AVAILABLE, "10.0"),
			Arguments.of("4-경계 min-1 (이번달)", cell(5, 9), cell(10, 20),
				Status.INSUFFICIENT_SAMPLE, null),
			Arguments.of("5-경계 min-1 (전월)", cell(10, 20), cell(5, 9),
				Status.INSUFFICIENT_SAMPLE, null),
			Arguments.of("6-전월 부재", cell(8, 12), null,
				Status.NO_PREVIOUS_PERIOD, null),
			Arguments.of("7-이번달 부재", null, cell(8, 12),
				Status.NO_DATA, null),
			Arguments.of("8-변화 없음 0.0 (null 아님)", cell(0, 15), cell(0, 12),
				Status.AVAILABLE, "0.0"),
			Arguments.of("9-반올림 HALF_UP", cell(10, 30), cell(15, 30),
				Status.AVAILABLE, "-16.7"),
			Arguments.of("10-반올림 하한", cell(1, 30), cell(0, 30),
				Status.AVAILABLE, "3.3")
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("goldens")
	void goldenCases(String name, Cell current, Cell previous,
		Status expectedStatus, String expectedDeltaPp) {

		WeaknessImprovement result = WeaknessImprovement.of(current, previous, MIN);

		assertThat(result.status()).isEqualTo(expectedStatus);
		assertThat(result.minimumSampleSize()).isEqualTo(MIN);
		if (expectedDeltaPp == null) {
			assertThat(result.accuracyDeltaPp()).isNull();
		} else {
			assertThat(result.accuracyDeltaPp()).isEqualByComparingTo(new BigDecimal(expectedDeltaPp));
		}
		// 🔴 AVAILABLE 이 아닌 상태의 deltaPp 는 null 이어야 한다.
		if (expectedStatus != Status.AVAILABLE) {
			assertThat(result.accuracyDeltaPp())
				.as("non-AVAILABLE deltaPp must be null, never 0.0")
				.isNull();
		}
	}

	private static Cell cell(int correct, int scored) {
		return new Cell(scored, correct);
	}
}
