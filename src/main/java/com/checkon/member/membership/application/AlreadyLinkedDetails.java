package com.checkon.member.membership.application;

/**
 * {@code CHILD_ALREADY_LINKED} 의 {@code details}.
 *
 * <p>🔴 <b>어느 학부모와 연결됐는지는 절대 담지 않는다.</b> "연결됨"이라는 사실 자체는 unique
 * 위반으로 이미 드러나고 설계 §7-2 가 그것만 허용한다. 그 이상은 남의 관계 정보다.</p>
 *
 * @param alreadyMine 내가 이미 연결한 자녀인가. 프론트는 이때 오류가 아니라 자녀 목록으로 보낸다
 */
public record AlreadyLinkedDetails(boolean alreadyMine) {
}
