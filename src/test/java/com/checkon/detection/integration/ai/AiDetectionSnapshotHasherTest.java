package com.checkon.detection.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
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
			request.alertContext(),
			request.detectionEvidence()
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
			request.alertContext().reversed(),
			request.detectionEvidence().reversed()
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
			request.alertContext(),
			request.detectionEvidence()
		);
	}

	@Test
	@DisplayName("Given AI v0.2 fixed evidence vector, When hashing, Then its SHA-256 matches exactly")
	void givenV02FixedEvidenceVector_whenHashing_thenMatchesAiSample() {
		assertThat(hasher.hash(aiV02FixedVector())).isEqualTo(
			"sha256:20826dda25119fd1cd96c712c2f6a3180fdbca51e8a3503341501f8b5bda3823"
		);
	}

	@Test
	@DisplayName("Given omitted or empty evidence, When hashing, Then both have the same hash")
	void givenOmittedOrEmptyEvidence_whenHashing_thenHashesAreEqual() {
		AiDetectionRequest full = aiV02FixedVector();
		AiDetectionRequest omitted = new AiDetectionRequest(
			full.snapshotMeta(), full.students(), full.learningEvents(), full.alertContext(), null
		);
		AiDetectionRequest empty = new AiDetectionRequest(
			full.snapshotMeta(), full.students(), full.learningEvents(), full.alertContext(), List.of()
		);

		assertThat(hasher.hash(omitted)).isEqualTo(hasher.hash(empty));
	}

	private AiDetectionRequest aiV02FixedVector() {
		LocalDate firstWeek = LocalDate.parse("2026-06-08");
		List<AiDetectionRequest.DetectionEvidence> evidence = new ArrayList<>();
		for (String studentRef : List.of(
			"student_alias_r2", "student_alias_r3", "student_alias_r5"
		)) {
			for (int index = 0; index < 10; index++) {
				LocalDate weekStart = firstWeek.plusWeeks(index);
				int submittedCount = "student_alias_r2".equals(studentRef) && index >= 7 ? 0 : 3;
				int activityCount = "student_alias_r3".equals(studentRef) && index == 9 ? 0 : 10;
				evidence.add(AiDetectionRequest.DetectionEvidence.assignmentWindow(
					"assignment_week_summary",
					"assignment-summary:" + studentRef + ":" + weekStart,
					studentRef, weekStart, 3, submittedCount
				));
				evidence.add(AiDetectionRequest.DetectionEvidence.weeklyActivity(
					"student_week_activity",
					"activity-summary:" + studentRef + ":" + weekStart,
					studentRef, weekStart, activityCount
				));
			}
		}
		evidence.add(AiDetectionRequest.DetectionEvidence.enrollmentTransition(
			"student_status_history",
			"status-history:student_alias_r5:2026-08-10T09:00:00+09:00",
			"student_alias_r5", OffsetDateTime.parse("2026-08-10T09:00:00+09:00"),
			"paused", "returned"
		));
		return new AiDetectionRequest(
			new AiDetectionRequest.SnapshotMeta(
				LocalDate.parse("2026-08-10"), "sha256:ignored", "normal",
				List.of(new AiDetectionRequest.ClassReference("class_alias_A"))
			),
			List.of(
				new AiDetectionRequest.StudentSnapshot(
					"student_alias_r2", "class_alias_A", 20, "enrolled", "granted"
				),
				new AiDetectionRequest.StudentSnapshot(
					"student_alias_r3", "class_alias_A", 20, "enrolled", "granted"
				),
				new AiDetectionRequest.StudentSnapshot(
					"student_alias_r5", "class_alias_A", 20, "returned", "granted"
				)
			),
			List.of(), List.of(), evidence
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
