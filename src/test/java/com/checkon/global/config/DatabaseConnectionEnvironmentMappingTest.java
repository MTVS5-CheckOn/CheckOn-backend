package com.checkon.global.config;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConnectionEnvironmentMappingTest {

	@Test
	void runtimeAndFlywayCredentialsUseSeparateRequiredEnvironmentVariables() throws IOException {
		var applicationProperties = new YamlPropertySourceLoader()
			.load("application", new FileSystemResource("src/main/resources/application.yaml"))
			.getFirst();

		assertThat(applicationProperties.getProperty("spring.datasource.url"))
			.isEqualTo("${DB_URL}");
		assertThat(applicationProperties.getProperty("spring.datasource.username"))
			.isEqualTo("${DB_USERNAME}");
		assertThat(applicationProperties.getProperty("spring.datasource.password"))
			.isEqualTo("${DB_PASSWORD}");

		assertThat(applicationProperties.getProperty("spring.flyway.url"))
			.isEqualTo("${FLYWAY_DB_URL:${DB_URL}}");
		assertThat(applicationProperties.getProperty("spring.flyway.user"))
			.isEqualTo("${FLYWAY_DB_USER}");
		assertThat(applicationProperties.getProperty("spring.flyway.password"))
			.isEqualTo("${FLYWAY_DB_PASSWORD}");
	}
}
