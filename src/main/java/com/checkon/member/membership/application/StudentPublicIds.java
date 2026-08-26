package com.checkon.member.membership.application;

import java.util.List;

import com.checkon.member.auth.domain.PublicStudentId;
import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;

/**
 * 공개 학생 ID 입력 판정.
 *
 * <p>🔴 <b>형식 오류(400)와 부재(404)를 가른다.</b> 열거 방어만 생각하면 둘을 404 로 묶고 싶어지지만
 * 분기표 §3 이 형식 오류를 {@code 400 INVALID_REQUEST} 로 못 박았다 — 그리고 그게 맞다.
 * 형식은 <b>서버에 묻지 않아도</b> 클라이언트가 판정할 수 있는 값이라, 400 을 준다고 해서
 * 존재 여부가 새지 않는다. 열거는 형식을 만족하는 입력으로 하는 것이고 그건 레이트 리밋이 막는다.</p>
 */
public final class StudentPublicIds {

	private static final String FIELD = "studentPublicId";

	private StudentPublicIds() {
	}

	public static String requireNormalized(String raw) {
		String normalized = PublicStudentId.normalize(raw);
		if (normalized == null) {
			String message = "studentPublicId must look like STU-XXXXXX";
			throw new MemberException(MemberErrorCode.INVALID_REQUEST, message,
				List.of(new FieldViolation(FIELD, message)));
		}
		return normalized;
	}
}
