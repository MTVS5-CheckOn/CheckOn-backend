package com.checkon.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Swagger UI가 코드 검증된 정적 OpenAPI 계약 하나만 읽도록 노출한다. */
@Configuration(proxyBeanMethods = false)
public class OpenApiResourceConfiguration implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/openapi/**")
			.addResourceLocations("classpath:/openapi/");
	}
}
