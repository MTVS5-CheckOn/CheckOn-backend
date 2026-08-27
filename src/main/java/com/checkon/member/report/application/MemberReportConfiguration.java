package com.checkon.member.report.application;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.checkon.member.common.storage.ObjectStoragePort;
import com.checkon.member.common.storage.infrastructure.LocalFileObjectStorageAdapter;
import com.checkon.member.common.storage.infrastructure.UnavailableObjectStorageAdapter;
import com.checkon.member.report.domain.ReportFileTokenCodec;

/**
 * member/report 경계의 정책값과 어댑터를 등록한다. 저장소에
 * {@code @ConfigurationPropertiesScan} 이 없어 각 경계가 자기 것을 등록한다.
 *
 * <p>🔴 <b>설정이 없으면 기동을 실패시키지 않는다.</b> {@code storageRoot} 나
 * {@code signingSecret} 이 비면 {@link UnavailableObjectStorageAdapter} 를 등록하고
 * 경고를 남긴다 — 목록·상세는 계속 {@code 200} 이고 file-access 만 {@code 503} 이다.
 * 저장소 미설정이 보고서 화면 전체를 죽이면 안 된다.</p>
 *
 * <p>🔴 <b>서명 키를 생성해 채우지 않는다.</b> 임시 키로 발급한 URL 은 재기동 후 전부 무효가
 * 되고 그 사실이 아무 데도 안 남는다. 「없는 값을 지어내지 마라」의 자물쇠판이다.</p>
 */
@Configuration
@EnableConfigurationProperties(MemberReportProperties.class)
public class MemberReportConfiguration {

	private static final Logger log = LoggerFactory.getLogger(MemberReportConfiguration.class);

	@Bean
	ObjectStoragePort memberReportObjectStorage(MemberReportProperties properties) {
		if (!properties.storageConfigured()) {
			log.warn("member.report.storage.unconfigured — file-access will answer 503."
				+ " set checkon.member.report.storage-root and .signing-secret to enable");
			return new UnavailableObjectStorageAdapter("not_configured");
		}
		return new LocalFileObjectStorageAdapter(Path.of(properties.storageRoot()));
	}

	/**
	 * 🔴 키가 없으면 codec 도 만들지 않는다 — 대신 아무 토큰도 통과시키지 못하는 codec 을
	 * 둔다. 발급 경로는 저장소가 이미 503 을 내므로 여기까지 오지 않고, 다운로드 경로는
	 * 서명이 절대 맞지 않아 <b>404</b> 다. 「설정이 없으면 전부 통과」가 되는 분기를 만들지
	 * 않는 것이 요점이다.
	 */
	@Bean
	ReportFileTokenCodec memberReportFileTokenCodec(MemberReportProperties properties) {
		if (properties.storageConfigured()) {
			return new ReportFileTokenCodec(properties.signingSecret());
		}
		return new ReportFileTokenCodec(UnusableSecret.value());
	}

	/**
	 * 미설정 상태에서 쓰는 키. 🔴 <b>발급에는 쓰이지 않는다</b>(저장소가 먼저 503 을 낸다).
	 * 프로세스마다 달라서 이 키로 만든 토큰은 재기동 후 무효다 — 그것이 의도다.
	 */
	private static final class UnusableSecret {

		private static final String VALUE = java.util.UUID.randomUUID().toString();

		private UnusableSecret() {
		}

		static String value() {
			return VALUE;
		}
	}
}
