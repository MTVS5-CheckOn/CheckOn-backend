package com.checkon.member.common.presentation;

/**
 * {@code 429} 응답의 {@code details}. 예외 처리기가 이 값으로 {@code Retry-After} 를 채운다.
 *
 * <p>🔴 오류별 예외 클래스를 새로 만들지 않으려고 details 에 담는다(코드 규칙 §5 —
 * 도메인 예외는 {@code MemberException} 하나로 통일).</p>
 */
public record RateLimitDetails(long retryAfterSeconds) {
}
