package com.checkon.member.common.storage;

/**
 * object storage 를 쓸 수 없다. 부재·권한·I/O·미설정·검증 상한 초과를 <b>하나로</b> 낸다.
 *
 * <p>🔴 구분하지 않는 이유 — 호출자의 처리가 모두 같다. 어느 쪽이든 URL 을 발급하지 않고
 * {@code 503 DEPENDENCY_UNAVAILABLE} 이다. 구분해서 응답에 실으면 저장소 내부 상태를
 * 밖에서 탐색할 수 있게 된다.</p>
 *
 * <p>🔴 {@code message} 에 {@code objectKey} 를 담지 않는다. 로그에도 키가 아니라 사유
 * 코드만 남긴다.</p>
 */
public class ObjectStorageUnavailableException extends RuntimeException {

	private final String reasonCode;

	public ObjectStorageUnavailableException(String reasonCode) {
		super("object storage unavailable: " + reasonCode);
		this.reasonCode = reasonCode;
	}

	public ObjectStorageUnavailableException(String reasonCode, Throwable cause) {
		super("object storage unavailable: " + reasonCode, cause);
		this.reasonCode = reasonCode;
	}

	/** 로그·메트릭에 쓰는 짧은 사유. 응답 본문에 넣지 않는다. */
	public String reasonCode() {
		return reasonCode;
	}
}
