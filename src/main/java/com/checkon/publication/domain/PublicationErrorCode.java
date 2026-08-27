package com.checkon.publication.domain;

import org.springframework.http.HttpStatus;

/**
 * 발행 경계가 내려보내는 오류 코드.
 *
 * <p>🔴 <b>어휘를 새로 만들지 않았다.</b> 값은 전부 member 의
 * {@code MemberErrorCode}(정본 {@code docs/MEMBER_ERROR_CODES.md}) 에 이미 있는 것이고,
 * 여기서는 <b>이름만 다시 선언</b>한다 — publication 은 member 패키지를 참조하지 않는다는
 * 경계 규칙 때문이다(참조 하나가 생기면 다음에 열 개가 된다).</p>
 *
 * <p>🔴 <b>왜 봉투가 {@code {"error":{...}}} 가 아닌가</b> — 이 API 는 {@code /api/v1/**} 에
 * 있고 그 경로의 401·403 은 Spring Security 단계에서
 * {@code SecurityErrorResponseWriter} 가 <b>top-level {@code {code, message}}</b> 로 쓴다.
 * 우리만 다른 모양을 쓰면 <b>한 엔드포인트가 실패 종류에 따라 두 가지 봉투</b>를 낸다.
 * 그래서 이웃(강사 API)과 보안 필터의 모양을 따른다.</p>
 */
public enum PublicationErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
	/**
	 * 🔴 이미 답변된 상담에 또 답하려 할 때. member 어휘에 「이미 답변됨」 전용 코드가 없어
	 * 「상태가 이미 넘어갔다」를 뜻하는 이 코드를 쓴다 — <b>완벽한 대응은 아니다.</b>
	 * 전용 코드를 만들려면 member 어휘를 늘려야 하고 그건 이 작업의 범위가 아니다.
	 */
	REVISION_CONFLICT(HttpStatus.CONFLICT),
	INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

	private final HttpStatus status;

	PublicationErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
