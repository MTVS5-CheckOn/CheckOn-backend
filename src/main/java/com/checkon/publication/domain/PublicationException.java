package com.checkon.publication.domain;

/** 발행 경계의 계약 위반. 🔴 {@code message} 에 상담 원문·학생 이름을 담지 않는다. */
public class PublicationException extends RuntimeException {

	private final PublicationErrorCode errorCode;

	public PublicationException(PublicationErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public PublicationErrorCode errorCode() {
		return errorCode;
	}

	public static PublicationException notFound(String message) {
		return new PublicationException(PublicationErrorCode.RESOURCE_NOT_FOUND, message);
	}

	public static PublicationException invalid(String message) {
		return new PublicationException(PublicationErrorCode.INVALID_REQUEST, message);
	}

	public static PublicationException conflict(String message) {
		return new PublicationException(PublicationErrorCode.REVISION_CONFLICT, message);
	}
}
