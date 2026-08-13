package com.checkon.problem.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.checkon.problem.application.ProblemDiagnosisNodeSelector.NodeCandidate;

@DisplayName("문제 출제 진단 node 선택 정책")
class ProblemDiagnosisNodeSelectorTest {
	private final ProblemDiagnosisNodeSelector selector = new ProblemDiagnosisNodeSelector();

	@Nested
	@DisplayName("Given 선택한 cell에 weak_confirmed와 suspect node가 함께 있을 때")
	class GivenConfirmedAndSuspectNodes {
		@Test
		@DisplayName("When 출제 node를 선택하면 Then weak_confirmed 전체만 ID 사전순으로 반환한다")
		void selectsEveryConfirmedNodeInCanonicalOrder() {
			var candidates = List.of(
				new NodeCandidate("language.node.z", "suspect", List.of("cell:language×infer")),
				new NodeCandidate("language.node.b", "weak_confirmed", List.of("cell:language×infer")),
				new NodeCandidate("language.node.a", "weak_confirmed", List.of("cell:language×infer"))
			);

			assertThat(selector.select("language", "INFER", candidates))
				.containsExactly("language.node.a", "language.node.b");
		}
	}

	@Nested
	@DisplayName("Given 선택한 cell에 suspect node만 있을 때")
	class GivenOnlySuspectNodes {
		@Test
		@DisplayName("When 출제 node를 선택하면 Then suspect 전체를 ID 사전순으로 반환한다")
		void fallsBackToEverySuspectNode() {
			var candidates = List.of(
				new NodeCandidate("language.node.b", "suspect", List.of("cell:language×concept")),
				new NodeCandidate("language.node.a", "suspect", List.of("cell:language×concept")),
				new NodeCandidate("reading.node", "weak_confirmed", List.of("cell:reading×concept"))
			);

			assertThat(selector.select("language", "concept", candidates))
				.containsExactly("language.node.a", "language.node.b");
		}
	}

	@Nested
	@DisplayName("Given 선택한 cell에 출제 가능한 node가 없을 때")
	class GivenNoEligibleNode {
		@Test
		@DisplayName("When 출제 node를 선택하면 Then 빈 결과로 AI 호출 불가를 표현한다")
		void returnsNoNode() {
			assertThat(selector.select("language", "concept", List.of(
				new NodeCandidate("language.node", "ok", List.of("cell:language×concept"))
			))).isEmpty();
		}
	}
}
