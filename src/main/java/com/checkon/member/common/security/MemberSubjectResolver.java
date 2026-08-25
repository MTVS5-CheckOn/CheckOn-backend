package com.checkon.member.common.security;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;

/**
 * {@code @CurrentMember MemberSubject} 를 만든다. 프로필 조회는 요청당 1회로 묶는다.
 *
 * <p>계정만 있고 프로필이 없는 상태는 인증 불가로 본다(설계 §5-2) — 404 로 존재를 노출하지 않고
 * {@code 401 AUTHENTICATION_REQUIRED} 를 낸다.</p>
 */
@Component
public class MemberSubjectResolver implements HandlerMethodArgumentResolver {

	private static final String CACHE_KEY = MemberSubjectResolver.class.getName() + ".subject";

	private final MemberPrincipalProvider principalProvider;
	private final MemberProfileDirectory profileDirectory;

	public MemberSubjectResolver(
		MemberPrincipalProvider principalProvider,
		MemberProfileDirectory profileDirectory
	) {
		this.principalProvider = principalProvider;
		this.profileDirectory = profileDirectory;
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
		Object cached = webRequest.getAttribute(CACHE_KEY, RequestAttributes.SCOPE_REQUEST);
		if (cached instanceof MemberSubject subject) {
			return subject;
		}
		MemberSubject resolved = resolve();
		webRequest.setAttribute(CACHE_KEY, resolved, RequestAttributes.SCOPE_REQUEST);
		return resolved;
	}

	private MemberSubject resolve() {
		MemberPrincipal principal = principalProvider.currentPrincipal()
			.orElseThrow(() -> new MemberException(
				MemberErrorCode.AUTHENTICATION_REQUIRED,
				"no member principal in security context"));

		UUID studentProfileId = null;
		UUID parentProfileId = null;
		if (principal.role() == MemberRole.STUDENT) {
			studentProfileId = profileDirectory.findStudentProfileId(principal.accountId())
				.orElseThrow(() -> new MemberException(
					MemberErrorCode.AUTHENTICATION_REQUIRED, "student profile is missing"));
		} else {
			parentProfileId = profileDirectory.findParentProfileId(principal.accountId())
				.orElseThrow(() -> new MemberException(
					MemberErrorCode.AUTHENTICATION_REQUIRED, "parent profile is missing"));
		}

		// 🔴 활성화 상태 테이블은 PR2 가 만든다. 그전에는 모르는 값이므로 null 을 그대로 둔다.
		return new MemberSubject(
			principal.accountId(),
			principal.role(),
			principal.sessionId(),
			studentProfileId,
			parentProfileId,
			null
		);
	}
}
