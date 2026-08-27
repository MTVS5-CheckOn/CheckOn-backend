package com.checkon.member.consultation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ConsultationSensitiveLoggingTest {

	private static final Path SOURCE = Path.of(
		"src/main/java/com/checkon/member/consultation/application/ParentConsultationService.java");
	private static final Pattern RAW_CONTENT_LOG = Pattern.compile(
		"(?i)(System\\.(?:out|err)\\.print(?:ln)?|log\\.[a-z]+)[^\\n]*content");

	@Test
	void 상담_원문을_로그나_표준출력에_남기지_않는다() throws IOException {
		assertThat(RAW_CONTENT_LOG.matcher(Files.readString(SOURCE)).find())
			.isFalse();
	}
}
