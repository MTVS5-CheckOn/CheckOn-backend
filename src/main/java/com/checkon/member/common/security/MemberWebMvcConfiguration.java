package com.checkon.member.common.security;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code @CurrentMember} 인자 해석기를 등록한다.
 *
 * <p>해석기는 {@code @CurrentMember MemberSubject} 파라미터에만 반응하므로 기존 컨트롤러의
 * 인자 바인딩에는 영향이 없다.</p>
 */
@Configuration
public class MemberWebMvcConfiguration implements WebMvcConfigurer {

	private final MemberSubjectResolver memberSubjectResolver;

	public MemberWebMvcConfiguration(MemberSubjectResolver memberSubjectResolver) {
		this.memberSubjectResolver = memberSubjectResolver;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(memberSubjectResolver);
	}
}
