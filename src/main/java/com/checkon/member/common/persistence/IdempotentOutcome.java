package com.checkon.member.common.persistence;

/**
 * 멱등 처리 결과. 컨트롤러는 이 값을 그대로 HTTP 응답으로 옮긴다.
 *
 * @param status 최초 처리의 상태 코드. 🔴 재생일 때도 <b>같은 값</b>이다(분기표 §0-4)
 * @param body   JSON 원문. 재생이면 저장된 문자열 그대로다
 * @param replayed 저장된 응답을 재생했는가. 로그·테스트용이며 응답 본문에는 넣지 않는다
 */
public record IdempotentOutcome(int status, String body, boolean replayed) {
}
