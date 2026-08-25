package com.checkon.member.common.persistence;

/**
 * 본 처리가 만든 응답. 🔴 상태 코드를 <b>본 처리가</b> 정한다 — 같은 오퍼레이션이 201 과 200 을
 * 모두 낼 수 있기 때문이다(초대 등록의 MB-04 멱등 경로). 가드가 상태를 고정하면 그 분기가 사라진다.
 */
public record IdempotentPayload(int status, Object body) {
}
