package com.checkon.member.common.presentation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * member 공통 정책값을 등록한다.
 *
 * <p>저장소에 {@code @ConfigurationPropertiesScan} 이 없어 각 경계가 자기 것을 등록한다 —
 * {@code MemberAuthConfiguration} 과 같은 선례다.</p>
 */
@Configuration
@EnableConfigurationProperties(MemberRateLimitProperties.class)
public class MemberCommonConfiguration {
}
