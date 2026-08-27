package com.checkon.member.learning.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * member/learning 경계의 정책값을 등록한다. {@link MemberAttemptProperties} 를 노출한다.
 *
 * <p>저장소에 {@code @ConfigurationPropertiesScan} 이 없어 각 경계가 자기 것을 등록한다 —
 * {@code MemberAuthConfiguration} 이 같은 선례다.</p>
 */
@Configuration
@EnableConfigurationProperties({MemberAttemptProperties.class, MemberHomeProperties.class})
public class MemberLearningConfiguration {
}
