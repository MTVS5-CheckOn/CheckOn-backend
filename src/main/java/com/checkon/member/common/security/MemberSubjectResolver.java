package com.checkon.member.common.security;

import java.util.Optional;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.checkon.member.auth.application.MemberSubjectLoader;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;

/**
 * {@code @CurrentMember MemberSubject} 를 만든다. 프로필 조회는 요청당 1회로 묶는다.
 *
 * <p>계정만 있고 프로필이 없는 상태는 인증 불가로 본다(설계 §5-2) — 404 로 존재를 노출하지 않고
 * {@code 401 AUTHENTICATION_REQUIRED} 를 낸다.</p>
 *
 * <p>🔴 캐시를 {@code RequestContextHolder} 로 읽는다. {@code NativeWebRequest} 로만 캐싱하면
 * 인터셉터({@code StudentActivationGuard})가 캐시를 못 봐서 <b>같은 요청에 조회가 두 번</b> 돈다.
 * 두 번째 조회가 첫 번째와 다른 값을 볼 여지도 생긴다.</p>
 */
@Component
public class MemberSubjectResolver implements HandlerMethodArgumentResolver {

	private static final String CACHE_KEY = MemberSubjectResolver.class.getName() + ".subject";

	private final MemberPrincipalProvider principalProvider;
	private final MemberSubjectLoader subjectLoader;

	public MemberSubjectResolver(
		MemberPrincipalProvider principalProvider,
		MemberSubjectLoader subjectLoader
	) {
		this.principalProvider = principalProvider;
		this.subjectLoader = subjectLoader;
	}

	/**
	 * 인터셉터 등 컨트롤러 인자 밖에서 주체가 필요할 때 쓴다.
	 *
	 * <p>인증되지 않았거나 member 역할이 아니면 비어 있다 — 예외를 던지지 않는다.
	 * 인증 판정은 보안 체인이 이미 했고, 여기서 다시 401 을 내면 판정이 두 곳으로 갈린다.</p>
	 */
	public Optional<MemberSubject> currentSubject() {
		if (principalProvider.currentPrincipal().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(resolveCached());
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentMember.class)
			&& MemberSubject.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(
		MethodParameter parameter,
		ModelAndViewContainer container,
		NativeWebRequest webRequest,
		WebDataBinderFactory binderFactory
	) {
		return resolveCached();
	}

	private MemberSubject resolveCached() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes == null) {
			// 요청 밖에서 불릴 일은 없지만, 캐시가 없다고 조용히 통과시키지 않는다.
			return resolve();
		}
		Object cached = attributes.getAttribute(CACHE_KEY, RequestAttributes.SCOPE_REQUEST);
		if (cached instanceof MemberSubject subject) {
			return subject;
		}
		MemberSubject resolved = resolve();
		attributes.setAttribute(CACHE_KEY, resolved, RequestAttributes.SCOPE_REQUEST);
		return resolved;
	}

	private MemberSubject resolve() {
		MemberPrincipal principal = principalProvider.currentPrincipal()
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.AUTHENTICATION_REQUIRED,
				"no member principal in security context"));
		// 🔴 조회는 반드시 RLS 컨텍스트가 열린 트랜잭션 안이어야 한다. MemberSubjectLoader 참조.
		return subjectLoader.load(principal);
	}
}
