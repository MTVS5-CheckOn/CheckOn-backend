package com.checkon.member.learning.application;

import java.time.DateTimeException;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 학생 홈의 날짜 경계 설정. 기본값은 설계 정본의 {@code Asia/Seoul}. */
@ConfigurationProperties("checkon.member.home")
public record MemberHomeProperties(String zoneId) {

	public MemberHomeProperties {
		zoneId = zoneId == null || zoneId.isBlank() ? "Asia/Seoul" : zoneId;
		try {
			ZoneId.of(zoneId);
		}
		catch (DateTimeException invalid) {
			throw new IllegalArgumentException("zone-id must be a valid time zone", invalid);
		}
	}

	public ZoneId zone() {
		return ZoneId.of(zoneId);
	}
}
