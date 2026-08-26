package com.checkon.member.learning.application;

/**
 * 학습 중 학생 화면에 내려가는 선택지 한 개. 번호와 텍스트만 있다.
 *
 * <p>🔴 {@code misconceptionTag} 를 여기 담지 않는다. 오답 이유는 결과 화면에서만 보인다.</p>
 */
public record AttemptOption(int no, String text) {
}
