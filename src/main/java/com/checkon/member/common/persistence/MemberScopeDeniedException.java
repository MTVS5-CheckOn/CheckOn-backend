package com.checkon.member.common.persistence;

/**
 * 범위(scope)를 열려 했으나 관계 확인에 실패했다.
 *
 * <p>🔴 이 예외가 나면 세션에는 <b>아무것도 들어가지 않았다.</b> 확인과 세팅이 한 메서드에
 * 묶여 있어 확인을 건너뛸 방법이 없기 때문이다.</p>
 *
 * <p>호출부는 이를 {@code 404 RESOURCE_NOT_FOUND} 로 옮긴다 — 부재와 권한 없음을 구분하지
 * 않는다(설계 §6-4 불변식 3).</p>
 */
public class MemberScopeDeniedException extends RuntimeException {

	public MemberScopeDeniedException(String setting) {
		super("scope was not granted: " + setting);
	}
}
