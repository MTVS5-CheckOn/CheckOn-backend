package com.checkon.global.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("공통 페이지네이션 응답")
class PagedResponseTest {
	@Test
	@DisplayName("Given 중간 페이지가 있을 때 When 응답을 만들면 Then 실제 항목 수와 페이지 경계를 계산한다")
	void calculatesMetadataForMiddlePage() {
		var response = PagedResponse.of(List.of("A", "B"), 1, 2, 5);

		assertThat(response.items()).containsExactly("A", "B");
		assertThat(response.metadata().pageNumber()).isEqualTo(1);
		assertThat(response.metadata().pageSize()).isEqualTo(2);
		assertThat(response.metadata().itemCount()).isEqualTo(2);
		assertThat(response.metadata().totalItemCount()).isEqualTo(5);
		assertThat(response.metadata().totalPageCount()).isEqualTo(3);
		assertThat(response.metadata().isFirst()).isFalse();
		assertThat(response.metadata().isLast()).isFalse();
	}

	@Test
	@DisplayName("Given 빈 첫 페이지가 있을 때 When 응답을 만들면 Then 시작이자 종료 페이지로 표시한다")
	void marksEmptyFirstPageAsFirstAndLast() {
		var response = PagedResponse.of(List.of(), 0, 20, 0);

		assertThat(response.metadata().itemCount()).isZero();
		assertThat(response.metadata().totalPageCount()).isZero();
		assertThat(response.metadata().isFirst()).isTrue();
		assertThat(response.metadata().isLast()).isTrue();
	}
}
