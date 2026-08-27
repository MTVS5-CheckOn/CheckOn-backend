package com.checkon.member.common.storage;

/**
 * object storage 가 알려주는 한 객체의 메타데이터.
 *
 * <p>🔴 {@code objectKey} 를 담지 않는다. 이 record 가 응답 DTO 로 새어도 키가 노출되지
 * 않도록 <b>타입에 자리를 두지 않는</b> 것이다(계약 {@code ReportFileAccess} 와 같은 원칙).</p>
 *
 * @param contentType 저장소가 보고한 MIME. 저장값과 다르면 발급하지 않는다
 * @param sizeBytes   저장소가 보고한 바이트 수. 저장값과 다르면 발급하지 않는다
 */
public record StoredObjectMetadata(String contentType, long sizeBytes) {
}
