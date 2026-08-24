package com.checkon.detection.application;

import java.util.Objects;
import java.util.UUID;

/** Server-owned stable tenant identifier used only for the Backend-to-AI contract. */
public record DetectionTenantKey(String value) {

	private static final String PREFIX = "teacher_";

	public DetectionTenantKey {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("value must not be blank");
		}
	}

	public static DetectionTenantKey fromTeacherProfileId(UUID teacherProfileId) {
		Objects.requireNonNull(
			teacherProfileId,
			"teacherProfileId must not be null"
		);
		// 운영 tenant key를 요청 헤더나 body에서 받으면 다른 강사의 실행 키를
		// 가장할 수 있다. 인증된 TeacherProfile ID로만 결정해 일별 멱등성과
		// 서버 간 X-Tenant-Id가 같은 테넌트 경계를 가리키게 한다.
		return new DetectionTenantKey(
			PREFIX + teacherProfileId.toString().replace("-", "")
		);
	}
}
