package com.checkon.publication.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * publication 경계의 정책값을 등록한다. 저장소에 {@code @ConfigurationPropertiesScan} 이 없어
 * 각 경계가 자기 것을 등록한다(member 의 여러 경계가 같은 선례다).
 */
@Configuration
@EnableConfigurationProperties(PublicationProperties.class)
public class PublicationConfiguration {
}
