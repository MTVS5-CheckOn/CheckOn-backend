package com.checkon.detection.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.checkon.detection.integration.ai.dto.AiDetectionRequest;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiDetectionSnapshotHasherTest {

	private final ObjectMapper objectMapper = JsonMapper.builder()
		.findAndAddModules()
		.build();

	private final AiDetectionSnapshotHasher hasher = new AiDetectionSnapshotHasher();

	@Test
	void sameSnapshotAlwaysCreatesSameSha256Hash() throws Exception {
		AiDetectionRequest request = readRequest();

		String first = hasher.hash(request);
		String retry = hasher.hash(request);

		assertThat(retry).isEqualTo(first);
		assertThat(first).matches("sha256:[0-9a-f]{64}");
	}

	@Test
	void existingSnapshotHashDoesNotAffectCalculation() throws Exception {
		AiDetectionRequest request = readRequest();
		AiDetectionRequest changedHashOnly = withSnapshotHash(
			request,
			"sha256:this-value-is-excluded"
		);

		assertThat(hasher.hash(changedHashOnly)).isEqualTo(hasher.hash(request));
	}

	@Test
	void changedLearningContentCreatesDifferentHash() throws Exception {
		AiDetectionRequest request = readRequest();
		AiDetectionRequest.LearningEventSnapshot original = request.learningEvents().getFirst();
		AiDetectionRequest.LearningEventSnapshot changedEvent =
			new AiDetectionRequest.LearningEventSnapshot(
				original.recordId(),
				original.studentRef(),
				original.type(),
				original.occurredAt(),
				!original.correct(),
				original.durationSec(),
				original.passageWordCount(),
				original.areaTag(),
				original.subjectTrack(),
				original.typeTag(),
				original.itemFormat(),
				original.assignmentTitleText(),
				original.source()
			);
		AiDetectionRequest changedRequest = new AiDetectionRequest(
			request.snapshotMeta(),
			request.students(),
			List.of(changedEvent, request.learningEvents().get(1)),
			request.alertContext()
		);

		assertThat(hasher.hash(changedRequest)).isNotEqualTo(hasher.hash(request));
	}

	@Test
	void arrayOrderDoesNotAffectHash() throws Exception {
		AiDetectionRequest request = readRequest();
		AiDetectionRequest reordered = new AiDetectionRequest(
			new AiDetectionRequest.SnapshotMeta(
				request.snapshotMeta().weekStart(),
				request.snapshotMeta().snapshotHash(),
				request.snapshotMeta().termContext(),
				request.snapshotMeta().classes().reversed()
			),
			request.students().reversed(),
			request.learningEvents().reversed(),
			request.alertContext().reversed()
		);

		assertThat(hasher.hash(reordered)).isEqualTo(hasher.hash(request));
	}

	private AiDetectionRequest withSnapshotHash(
		AiDetectionRequest request,
		String snapshotHash
	) {
		return new AiDetectionRequest(
			new AiDetectionRequest.SnapshotMeta(
				request.snapshotMeta().weekStart(),
				snapshotHash,
				request.snapshotMeta().termContext(),
				request.snapshotMeta().classes()
			),
			request.students(),
			request.learningEvents(),
			request.alertContext()
		);
	}

	private AiDetectionRequest readRequest() throws Exception {
		try (InputStream input = getClass()
			.getClassLoader()
			.getResourceAsStream("ai/detect-contract-request.json")) {
			assertThat(input).isNotNull();
			return objectMapper.readValue(input, AiDetectionRequest.class);
		}
	}
}
