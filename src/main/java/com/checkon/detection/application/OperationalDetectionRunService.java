package com.checkon.detection.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkon.detection.application.PrepareDetectionRunService.PreparedDetectionRun;
import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.infrastructure.kafka.AiTenantAliasService;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.learning.application.LearningRecordSnapshotService;

@Service
public class OperationalDetectionRunService {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int ANALYSIS_DAYS = 56;
	private static final String DEFAULT_TERM_CONTEXT = "normal";

	private final LearningRecordSnapshotService snapshotService;
	private final PrepareDetectionRunService prepareService;
	private final KafkaDetectionRequestService kafkaRequestService;
	private final AiTenantAliasService tenantAliasService;

	public OperationalDetectionRunService(
		LearningRecordSnapshotService snapshotService,
		PrepareDetectionRunService prepareService,
		KafkaDetectionRequestService kafkaRequestService,
		AiTenantAliasService tenantAliasService
	) {
		this.snapshotService = snapshotService;
		this.prepareService = prepareService;
		this.kafkaRequestService = kafkaRequestService;
		this.tenantAliasService = tenantAliasService;
	}

	public OperationalDetectionRun execute(UUID teacherId, LocalDate analysisDate) {
		Objects.requireNonNull(teacherId, "teacherId must not be null");
		Objects.requireNonNull(analysisDate, "analysisDate must not be null");

		LocalDate fromDate = analysisDate.minusDays(ANALYSIS_DAYS - 1L);
		Instant fromInclusive = fromDate.atStartOfDay(SERVICE_ZONE).toInstant();
		Instant toExclusive = analysisDate.plusDays(1)
			.atStartOfDay(SERVICE_ZONE)
			.toInstant();
		LocalDate weekStart = analysisDate.with(
			TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
		);

		// 클라이언트가 학습 snapshot을 만들면 다른 학생 기록을 섞거나 개인정보를
		// 추가할 수 있다. 인증된 강사의 서버 소유 Learning Record를 RLS 안에서
		// 조회하고, 학생은 AI alias로 바꾼 최소 계약만 생성한다.
		AiDetectionRequest snapshot = snapshotService.build(
			teacherId,
			weekStart,
			DEFAULT_TERM_CONTEXT,
			fromInclusive,
			toExclusive
		);
		// A zero-activity week is now a citable fact in detection_evidence. Only
		// skip a run when there is no active, consent-eligible student at all.
		if (snapshot.students().isEmpty()) {
			throw new NoLearningRecordsException();
		}

		String tenantAlias = tenantAliasService.getOrCreate(teacherId);
		PreparedDetectionRun prepared = prepareService.prepare(
			teacherId,
			tenantAlias,
			analysisDate,
			snapshot
		);
		if (prepared.status() == DetectionRunStatus.SUCCEEDED) {
			return new OperationalDetectionRun(
				prepared.runId(), DetectionRunStatus.SUCCEEDED, analysisDate,
				prepared.created(), 0, Outcome.ALREADY_COMPLETED
			);
		}
		if (prepared.status() == DetectionRunStatus.REQUESTED) {
			return new OperationalDetectionRun(
				prepared.runId(), DetectionRunStatus.REQUESTED, analysisDate,
				prepared.created(), 0, Outcome.DUPLICATE
			);
		}

		KafkaDetectionRequestService.EnqueuedDetectionRequest enqueued =
			kafkaRequestService.enqueue(teacherId, tenantAlias, prepared.runId());
		return new OperationalDetectionRun(
			prepared.runId(),
			DetectionRunStatus.REQUESTED,
			analysisDate,
			prepared.created(),
			enqueued.attemptNumber(),
			Outcome.ENQUEUED
		);
	}

	public enum Outcome {
		ENQUEUED,
		ALREADY_COMPLETED,
		DUPLICATE
	}

	public record OperationalDetectionRun(
		UUID runId,
		DetectionRunStatus status,
		LocalDate analysisDate,
		boolean created,
		int attemptNumber,
		Outcome outcome
	) {
	}
}
