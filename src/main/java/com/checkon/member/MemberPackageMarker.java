package com.checkon.member;

/**
 * member 경계의 앵커. 인스턴스를 만들지 않는다.
 *
 * <p>{@code @RestControllerAdvice(basePackageClasses = MemberPackageMarker.class)} 로
 * 예외 처리 범위를 member 패키지에만 묶고, 코드 규칙 테스트가 패키지 루트를 찾는 데도 쓴다.</p>
 */
public interface MemberPackageMarker {
}
