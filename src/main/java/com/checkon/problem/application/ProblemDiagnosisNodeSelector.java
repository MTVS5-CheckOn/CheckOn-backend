package com.checkon.problem.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class ProblemDiagnosisNodeSelector {
	private static final String TEMPORARILY_UNSUPPORTED_NODE = "language.grammar.fortition";
	private static final BigDecimal NO_SCORE = BigDecimal.ZERO;

	public List<String> select(String areaTag, String typeTag, int count,
		List<NodeCandidate> candidates, List<PropagatedCandidate> propagatedCandidates) {
		if (count < 1) return List.of();
		String cellBasis = "cell:" + normalize(areaTag) + "×" + normalize(typeTag);
		List<NodeCandidate> matching = candidates == null ? List.of() : candidates.stream()
			.filter(Objects::nonNull)
			.filter(candidate -> candidate.basis() != null && candidate.basis().contains(cellBasis))
			.filter(candidate -> isSupported(candidate.nodeId()))
			.toList();
		List<PropagatedCandidate> propagated = propagatedCandidates == null ? List.of() : propagatedCandidates.stream()
			.filter(Objects::nonNull)
			.filter(candidate -> isSupported(candidate.nodeId()))
			.toList();
		Map<String, BigDecimal> scoresByNode = new HashMap<>();
		for (PropagatedCandidate candidate : propagated) {
			scoresByNode.put(candidate.nodeId(), candidate.score());
		}

		List<String> confirmed = idsForVerdict(matching, "weak_confirmed", scoresByNode);
		List<String> selected = new ArrayList<>();
		appendUpTo(selected, confirmed, count);

		Set<String> evidenceNodeIds = new HashSet<>();
		for (NodeCandidate candidate : matching) {
			String verdict = normalize(candidate.verdict());
			if ("weak_confirmed".equals(verdict) || "suspect".equals(verdict)) {
				evidenceNodeIds.add(candidate.nodeId());
			}
		}
		List<String> propagatedRoots = propagated.stream()
			.filter(candidate -> candidate.fromNodes() != null
				&& candidate.fromNodes().stream().anyMatch(evidenceNodeIds::contains))
			.sorted(propagatedOrder())
			.map(PropagatedCandidate::nodeId)
			.toList();
		appendUpTo(selected, propagatedRoots, count);
		return List.copyOf(selected);
	}

	private static List<String> idsForVerdict(List<NodeCandidate> candidates, String verdict,
		Map<String, BigDecimal> scoresByNode) {
		List<String> selected = new ArrayList<>();
		for (NodeCandidate candidate : candidates) {
			if (candidate.nodeId() != null && !candidate.nodeId().isBlank()
				&& verdict.equals(normalize(candidate.verdict()))) {
				selected.add(candidate.nodeId().trim());
			}
		}
		selected.sort(Comparator
			.<String, BigDecimal>comparing(node -> score(scoresByNode.get(node))).reversed()
			.thenComparing(Comparator.naturalOrder()));
		return List.copyOf(selected);
	}
	private static Comparator<PropagatedCandidate> propagatedOrder() {
		return Comparator.comparing(PropagatedCandidate::score).reversed()
			.thenComparing(PropagatedCandidate::nodeId);
	}
	private static void appendUpTo(List<String> target, List<String> candidates, int count) {
		for (String candidate : candidates) {
			if (target.size() == count) return;
			if (!target.contains(candidate)) target.add(candidate);
		}
	}
	private static boolean isSupported(String nodeId) {
		return nodeId != null && !nodeId.isBlank() && !TEMPORARILY_UNSUPPORTED_NODE.equals(nodeId.trim());
	}
	private static BigDecimal score(BigDecimal value) { return value == null ? NO_SCORE : value; }

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	public record NodeCandidate(String nodeId, String verdict, List<String> basis) { }
	public record PropagatedCandidate(String nodeId, BigDecimal score, List<String> fromNodes) {
		public PropagatedCandidate {
			score = score == null ? NO_SCORE : score;
			fromNodes = fromNodes == null ? List.of() : List.copyOf(fromNodes);
		}
	}
}
