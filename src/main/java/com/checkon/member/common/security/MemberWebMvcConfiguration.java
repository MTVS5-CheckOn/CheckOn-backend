package com.checkon.member.common.security;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code @CurrentMember} 인자 해석기와 학생 활성화 guard 를 등록한다.
 *
 * <p>해석기는 {@code @CurrentMember MemberSubject} 파라미터에만 반응하므로 기존 컨트롤러의
 * 인자 바인딩에는 영향이 없다.</p>
 *
 * <p>🔴 guard 의 {@code addPathPatterns} 를 {@code /api/v1/member/**} 로 좁힌다. 좁히지 않으면
 * 팀원의 {@code /api/v1/**} 전체에 인터셉터가 걸린다 — 무접촉 규칙 위반이고, 인증 주체가
 * 없는 요청마다 쓸데없이 조회가 돈다.</p>
 */
@Configuration
public class MemberWebMvcConfiguration implements WebMvcConfigurer {

	private static final String MEMBER_PATHS = "/api/v1/member/**";

	private final MemberSubjectResolver memberSubjectResolver;
	private final StudentActivationGuard studentActivationGuard;

	public MemberWebMvcConfiguration(
		MemberSubjectResolver memberSubjectResolver,
		StudentActivationGuard studentActivationGuard
	) {
		this.memberSubjectResolver = memberSubjectResolver;
		this.studentActivationGuard = studentActivationGuard;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(memberSubjectResolver);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(studentActivationGuard).addPathPatterns(MEMBER_PATHS);
	}
}
