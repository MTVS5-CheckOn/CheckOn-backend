package com.checkon.member.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.checkon.member.analytics.domain.TagNormalizer;

final class TagNormalizerTest {

	@Test
	void areaLowersAndValidates() {
		assertThat(TagNormalizer.normalizeArea("LANGUAGE")).isEqualTo("language");
		assertThat(TagNormalizer.normalizeArea(" media ")).isEqualTo("media");
	}

	@Test
	void unmappedAreaDropped() {
		// 🔴 구버전 6영역 매핑을 만들지 않는다. 소리 없이 버린다.
		assertThat(TagNormalizer.normalizeArea("speech")).isNull();
		assertThat(TagNormalizer.normalizeArea("history")).isNull();
	}

	@Test
	void typeLowersAndValidates() {
		assertThat(TagNormalizer.normalizeType("FACT")).isEqualTo("fact");
		assertThat(TagNormalizer.normalizeType("Infer")).isEqualTo("infer");
	}

	@Test
	void unmappedTypeDropped() {
		assertThat(TagNormalizer.normalizeType("apply")).isNull(); // v1 미생산
		assertThat(TagNormalizer.normalizeType("xyz")).isNull();
	}

	@Test
	void nullAndBlankReturnNull() {
		assertThat(TagNormalizer.normalizeArea(null)).isNull();
		assertThat(TagNormalizer.normalizeArea("   ")).isNull();
		assertThat(TagNormalizer.normalizeType(null)).isNull();
	}
}
