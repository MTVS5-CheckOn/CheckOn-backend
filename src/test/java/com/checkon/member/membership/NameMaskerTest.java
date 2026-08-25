package com.checkon.member.membership;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.checkon.member.membership.application.NameMasker;

/**
 * 🔴 이 스위트의 핵심 단언은 마지막 하나다 — <b>어떤 입력에서도 원본이 그대로 나가지 않는다.</b>
 * 두 글자 이름을 통과시키는 구현이 흔한 실패라 길이별로 나눠 못 박는다.
 */
class NameMaskerTest {

	@Test
	@DisplayName("세 글자 이름은 가운데만 가린다 — 계약 예시 김*수")
	void masksMiddleOfThreeLetterName() {
		assertThat(NameMasker.mask("김민수")).isEqualTo("김*수");
	}

	@Test
	@DisplayName("네 글자 이상은 첫 글자와 마지막 글자만 남긴다")
	void masksMiddleOfLongerName() {
		assertThat(NameMasker.mask("남궁민수")).isEqualTo("남**수");
		assertThat(NameMasker.mask("Alexander")).isEqualTo("A*******r");
	}

	@Test
	@DisplayName("🔴 두 글자 이름도 가린다 — 그냥 통과시키면 ID 열거로 이름을 수집할 수 있다")
	void masksTwoLetterName() {
		assertThat(NameMasker.mask("김수")).isEqualTo("김*");
	}

	@Test
	@DisplayName("한 글자 이름은 통째로 가린다 — 남길 수 있는 정보가 없다")
	void masksSingleLetterName() {
		assertThat(NameMasker.mask("김")).isEqualTo("*");
	}

	@Test
	@DisplayName("공백은 접은 뒤 가린다 — 띄어쓰기로 원형이 새지 않는다")
	void collapsesWhitespaceBeforeMasking() {
		assertThat(NameMasker.mask("  김 민 수  ")).isEqualTo("김*수");
	}

	@Test
	@DisplayName("값이 없으면 null 이다 — 빈 문자열로 채우지 않는다")
	void returnsNullWhenNothingToMask() {
		assertThat(NameMasker.mask(null)).isNull();
		assertThat(NameMasker.mask("   ")).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"김", "김수", "김민수", "남궁민수", "Alexander", "김 민 수", "이순신장군"})
	@DisplayName("🔴 어떤 입력에서도 원본 문자열을 그대로 반환하지 않는다")
	void neverReturnsRawInput(String raw) {
		assertThat(NameMasker.mask(raw))
			.as("원본이 그대로 나가면 마스킹이 아무 일도 하지 않은 것이다")
			.isNotEqualTo(raw)
			.isNotEqualTo(raw.replaceAll("\\s+", ""));
	}
}
