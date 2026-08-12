package com.checkon.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemGenerationStatusTest {

	@Test
	@DisplayName("Given 일부 child만 성공한 부모일 때 When 상태를 판정하면 Then 부분 성공은 종단 상태다")
	void partialSuccessIsTerminal() {
		assertThat(ProblemGenerationStatus.PARTIAL_SUCCESS.terminal()).isTrue();
	}

	@Test
	@DisplayName("Given child 실행 상태일 때 When 부모 실패 집계 여부를 판정하면 Then 취소와 근거 부족은 실패로 세지 않는다")
	void distinguishesFailuresUsedByParentAggregation() {
		assertThat(ProblemGenerationExecutionStatus.FAILED.failureForParentAggregation()).isTrue();
		assertThat(ProblemGenerationExecutionStatus.TIMED_OUT.failureForParentAggregation()).isTrue();
		assertThat(ProblemGenerationExecutionStatus.DELIVERY_FAILED.failureForParentAggregation()).isTrue();
		assertThat(ProblemGenerationExecutionStatus.CANCELLED.failureForParentAggregation()).isFalse();
		assertThat(ProblemGenerationExecutionStatus.REJECTED_INSUFFICIENT.failureForParentAggregation()).isFalse();
	}
}
