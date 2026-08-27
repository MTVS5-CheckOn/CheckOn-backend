package com.checkon.member.common.storage;

import java.io.InputStream;

/**
 * 발행된 보고서 PDF 바이트가 사는 곳을 여는 출력 포트.
 *
 * <p>🔴 <b>member 는 PDF 를 만들지 않는다.</b> 발행자가 올려 둔 파일을 참조하고, 서명 URL 을
 * 발급하고, 토큰으로 내려줄 뿐이다. PDF 생성·파싱 라이브러리를 추가하지 않는다 —
 * {@code build.gradle} 은 무접촉이다(PR1).</p>
 *
 * <p>🔴 구현은 {@code LocalFileObjectStorageAdapter} 하나뿐이다. S3 호환 구현은 SDK 의존성이
 * 필요하고 그것 역시 {@code build.gradle} 을 건드린다. 쓰지 않을 빈 껍데기 어댑터를 만들지
 * 않는다 — 인터페이스가 고정돼 있으면 나중에 추가하는 건 파일 하나다(MB-52).</p>
 *
 * <p>모든 실패는 {@link ObjectStorageUnavailableException} 하나로 낸다. 「없음」과 「장애」를
 * 호출자가 구분하지 않는다 — 어느 쪽이든 URL 을 발급하지 않기 때문이다.</p>
 */
public interface ObjectStoragePort {

	/**
	 * 존재·MIME·크기를 확인한다.
	 *
	 * @throws ObjectStorageUnavailableException 없거나 읽을 수 없을 때
	 */
	StoredObjectMetadata head(String objectKey);

	/**
	 * 스트리밍용 입력. 호출자가 닫는다.
	 *
	 * @throws ObjectStorageUnavailableException 없거나 읽을 수 없을 때
	 */
	InputStream open(String objectKey);

	/**
	 * 저장된 바이트를 실제로 읽어 sha256 을 다시 계산한다.
	 *
	 * <p>🔴 {@code maxVerifyBytes} 를 넘으면 <b>검증을 건너뛰고 통과시키지 않는다.</b>
	 * 예외를 던져 발급 자체를 막는다 — 검증할 수 없는 파일에 URL 을 주지 않는다.</p>
	 *
	 * @return {@code sha256:<64hex>}
	 * @throws ObjectStorageUnavailableException 없거나·읽을 수 없거나·상한을 넘을 때
	 */
	String sha256(String objectKey, long maxVerifyBytes);
}
