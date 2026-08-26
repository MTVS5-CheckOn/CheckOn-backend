package com.checkon.member.common.error;

/**
 * {@code INVALID_REQUEST} 의 {@code details} 원소. 프론트가 필드별 인라인 오류를 그린다.
 *
 * @param field  위반한 필드 경로
 * @param reason 위반 사유. 사용자 문구가 아니라 개발자용 설명이다
 */
public record FieldViolation(String field, String reason) {
}
