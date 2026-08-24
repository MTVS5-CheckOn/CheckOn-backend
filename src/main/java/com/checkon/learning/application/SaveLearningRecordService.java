package com.checkon.learning.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkon.learning.domain.LearningRecord;

/**
 * 기존 Detection 및 테스트 호출자를 새 등록 유스케이스에 연결하는 호환 어댑터다.
 *
 * <p>기존 호출자가 엔티티 초안을 곧바로 전달하는 계약을 한 번에 깨지 않으면서도,
 * 테넌트 설정과 소유권 검증은 {@link RegisterLearningRecordService} 한 곳만 통과하게
 * 한다. 신규 HTTP 등록은 이 어댑터가 아니라 command 경계를 사용한다.</p>
 */
@Service
public class SaveLearningRecordService {
	private final RegisterLearningRecordService registrationService;

	public SaveLearningRecordService(RegisterLearningRecordService registrationService) {
		this.registrationService = registrationService;
	}

	public LearningRecord save(UUID authenticatedTeacherId, LearningRecord.Draft draft) {
		Objects.requireNonNull(authenticatedTeacherId, "authenticatedTeacherId must not be null");
		Objects.requireNonNull(draft, "draft must not be null");
		// 호환 호출자가 가진 teacherId도 인증 주체와 다르면 새 유스케이스에
		// 진입시키지 않는다. 신규 API의 요청 본문에는 teacherId 자체가 없다.
		if (!authenticatedTeacherId.equals(draft.teacherId()))
			throw new IllegalArgumentException("teacherId must match authenticated teacher");
		return registrationService.registerRecord(authenticatedTeacherId,
			new RegisterLearningRecordCommand(
				draft.studentId(), draft.classGroupId(), draft.recordType(), draft.occurredAt(),
				draft.sourceType(), draft.externalRecordRef(), draft.correct(), draft.durationSec(),
				draft.passageWordCount(), draft.areaTag(), draft.subjectTrack(), draft.typeTag(),
				draft.itemFormat(), draft.assignmentTitleText()
				));
	}
}
