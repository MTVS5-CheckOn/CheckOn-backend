package com.checkon.member.common.notification;

/**
 * 알림 발행 경계.
 *
 * <p>🔴 발행은 <b>업무 트랜잭션 안에서</b> 한다. 별도 트랜잭션이면 자녀 등록이 롤백돼도
 * 알림이 남는다 — 그 순간 사용자는 "존재하지 않는 자녀에 대한 알림"을 본다.</p>
 *
 * <p>🔴 중복은 예외가 아니라 <b>무시</b>다. 같은 {@code (sourceType, sourceId, recipientAccountId)}
 * 로 두 번 발행되면 두 번째는 조용히 삭제된다({@code ON CONFLICT DO NOTHING}).
 * 계약 §12 "알림 — 중복 무시" 그대로. 23505 를 5xx 로 올리면 반려.</p>
 */
public interface NotificationPort {

	/** 발행. 반환값 없음 — 중복은 무시된다. */
	void publish(NotificationRequest request);
}
