package com.checkon.problem.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class ProblemDiagnosisNodeSelector {

	public List<String> select(String areaTag, String typeTag, List<NodeCandidate> candidates) {
		String cellBasis = "cell:" + normalize(areaTag) + "×" + normalize(typeTag);
		List<NodeCandidate> matching = candidates == null ? List.of() : candidates.stream()
			.filter(Objects::nonNull)
			.filter(candidate -> candidate.basis() != null && candidate.basis().contains(cellBasis))
			.toList();
		List<String> confirmed = idsForVerdict(matching, "weak_confirmed");
		return confirmed.isEmpty() ? idsForVerdict(matching, "suspect") : confirmed;
	}

	private static List<String> idsForVerdict(List<NodeCandidate> candidates, String verdict) {
		List<String> selected = new ArrayList<>();
		for (NodeCandidate candidate : candidates) {
			if (candidate.nodeId() != null && !candidate.nodeId().isBlank()
				&& verdict.equals(normalize(candidate.verdict()))) {
				selected.add(candidate.nodeId().trim());
			}
		}
		selected.sort(Comparator.naturalOrder());
		return List.copyOf(selected);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	public record NodeCandidate(String nodeId, String verdict, List<String> basis) { }
}
