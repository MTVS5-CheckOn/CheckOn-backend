package com.checkon.problem.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.checkon.problem.application.ProblemDiagnosisNodeSelector.NodeCandidate;
import com.checkon.problem.application.ProblemDiagnosisNodeSelector.PropagatedCandidate;

@DisplayName("문제 출제 진단 node 선택 정책")
class ProblemDiagnosisNodeSelectorTest {
	private final ProblemDiagnosisNodeSelector selector = new ProblemDiagnosisNodeSelector();

	@Nested
	@DisplayName("Given 선택한 cell에 직접 확정 근거가 있을 때")
	class GivenConfirmedEvidence {
		@Test
		@DisplayName("When 요청 개수만큼 선택하면 Then 확정 node를 우선하고 나머지를 역전파 점수순으로 채운다")
		void prioritizesConfirmedNodesAndFillsFromPropagation() {
			var nodes = List.of(
				new NodeCandidate("language.node.confirmed", "weak_confirmed", List.of("cell:language×infer")),
				new NodeCandidate("language.node.suspect", "suspect", List.of("cell:language×infer"))
			);
			var propagated = List.of(
				propagated("language.root.high", "3.2", "language.node.suspect"),
				propagated("language.node.confirmed", "1.0", "language.node.confirmed")
			);

			assertThat(selector.select("language", "INFER", 2, nodes, propagated))
				.containsExactly("language.node.confirmed", "language.root.high");
		}
	}

	@Nested
	@DisplayName("Given 선택한 cell에 추정 근거만 있을 때")
	class GivenSuspectEvidence {
		@Test
		@DisplayName("When 출제 node를 선택하면 Then 관련 역전파 root 중 점수 상위 count개만 반환한다")
		void selectsOnlyTopScoredRelatedRoots() {
			var nodes = List.of(
				new NodeCandidate("language.node.a", "suspect", List.of("cell:language×concept")),
				new NodeCandidate("reading.node", "suspect", List.of("cell:reading×concept"))
			);
			var propagated = List.of(
				propagated("language.root.second", "2.0", "language.node.a"),
				propagated("language.root.first", "3.0", "language.node.a"),
				propagated("language.root.third", "1.0", "language.node.a"),
				propagated("reading.root", "9.0", "reading.node")
			);

			assertThat(selector.select("language", "concept", 2, nodes, propagated))
				.containsExactly("language.root.first", "language.root.second");
		}

		@Test
		@DisplayName("When 생성 불가 node의 점수가 가장 높아도 Then 해당 node를 제외한다")
		void excludesTemporarilyUnsupportedNode() {
			var nodes = List.of(new NodeCandidate(
				"language.node.a", "suspect", List.of("cell:language×infer")));
			var propagated = List.of(
				propagated("language.grammar.fortition", "9.0", "language.node.a"),
				propagated("language.root.safe", "1.0", "language.node.a")
			);

			assertThat(selector.select("language", "infer", 1, nodes, propagated))
				.containsExactly("language.root.safe");
		}
	}

	@Nested
	@DisplayName("Given 요청 개수보다 출제 가능한 근거가 적을 때")
	class GivenInsufficientEvidence {
		@Test
		@DisplayName("When 출제 node를 선택하면 Then 확보된 node만 반환하여 호출 거절을 판단할 수 있게 한다")
		void returnsFewerNodesThanRequested() {
			var nodes = List.of(new NodeCandidate(
				"language.node", "suspect", List.of("cell:language×concept")));

			assertThat(selector.select("language", "concept", 2, nodes,
				List.of(propagated("language.root", "1.0", "language.node"))))
				.containsExactly("language.root");
		}
	}

	private static PropagatedCandidate propagated(String nodeId, String score, String... fromNodes) {
		return new PropagatedCandidate(nodeId, new BigDecimal(score), List.of(fromNodes));
	}
}
