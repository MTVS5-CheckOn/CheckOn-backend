package com.checkon.member.auth.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * member 인증 정책값을 등록한다.
 *
 * <p>저장소에 {@code @ConfigurationPropertiesScan} 이 없어 각 경계가 자기 것을 등록한다 —
 * {@code CounselDraftKafkaConfiguration} 등이 같은 선례다. {@code application.yaml} 은
 * 팀원 소유라 건드리지 않고, 값이 없으면 레코드가 기본값을 채운다.</p>
 */
@Configuration
@EnableConfigurationProperties(MemberAuthProperties.class)
public class MemberAuthConfiguration {
}
