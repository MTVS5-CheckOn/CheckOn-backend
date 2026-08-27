package com.checkon.member.profile.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MemberProfileProperties.class)
public class MemberProfileConfiguration {
}
