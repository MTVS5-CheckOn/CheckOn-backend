package com.checkon.member.analytics.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * member/analytics 경계의 정책값을 등록한다. 저장소에 {@code @ConfigurationPropertiesScan} 이
 * 없어 각 경계가 자기 것을 등록한다({@link com.checkon.member.question.application
 * .MemberQuestionConfiguration} 이 같은 선례다).
 */
@Configuration
@EnableConfigurationProperties(MemberMetricsProperties.class)
public class MemberAnalyticsConfiguration {
}
