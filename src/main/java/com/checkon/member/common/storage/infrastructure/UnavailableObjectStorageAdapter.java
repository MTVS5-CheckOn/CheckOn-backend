package com.checkon.member.common.storage.infrastructure;

import java.io.InputStream;

import com.checkon.member.common.storage.ObjectStoragePort;
import com.checkon.member.common.storage.ObjectStorageUnavailableException;
import com.checkon.member.common.storage.StoredObjectMetadata;

/**
 * 저장소가 설정되지 않았을 때 등록되는 어댑터. 모든 호출이 「사용 불가」다.
 *
 * <p>🔴 <b>기동을 실패시키지 않는다.</b> 저장소 미설정이 목록·상세까지 죽이면 안 된다 —
 * PDF 는 보고서의 한 부분이고, 나머지는 DB 만으로 답할 수 있다. file-access 만 {@code 503} 이다.</p>
 *
 * <p>🔴 임시 root 를 만들거나 서명 키를 생성해 채우는 분기를 두지 않는다. 「없는 값을 지어내지
 * 마라」의 저장소판이다 — 임시 키로 발급된 URL 은 재기동 후 전부 무효가 되고, 그 사실이
 * 아무 데도 안 남는다.</p>
 */
public class UnavailableObjectStorageAdapter implements ObjectStoragePort {

	private final String reasonCode;

	public UnavailableObjectStorageAdapter(String reasonCode) {
		this.reasonCode = reasonCode;
	}

	@Override
	public StoredObjectMetadata head(String objectKey) {
		throw new ObjectStorageUnavailableException(reasonCode);
	}

	@Override
	public InputStream open(String objectKey) {
		throw new ObjectStorageUnavailableException(reasonCode);
	}

	@Override
	public String sha256(String objectKey, long maxVerifyBytes) {
		throw new ObjectStorageUnavailableException(reasonCode);
	}
}
