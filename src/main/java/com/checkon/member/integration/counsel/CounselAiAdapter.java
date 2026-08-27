package com.checkon.member.integration.counsel;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.checkon.member.consultation.application.ConsultationTextMasker;

/**
 * 상담 원장을 adapter payload로 바꾸는 순수 변환기. HTTP·Kafka·LLM 클라이언트를 갖지 않는다.
 */
@Component
public class CounselAiAdapter {

	private static final Pattern TENANT = Pattern.compile("^tn_[0-9a-f]{32}$");
	private static final Pattern STUDENT = Pattern.compile("^st_[0-9a-f]{32}$");
	private static final Pattern PARENT = Pattern.compile("^pa_[0-9a-f]{32}$");
	private static final Pattern CLASS = Pattern.compile("^cl_[0-9a-f]{32}$");

	private final ConsultationTextMasker textMasker;

	public CounselAiAdapter(ConsultationTextMasker textMasker) {
		this.textMasker = textMasker;
	}

	public Optional<CounselDraftPayload> convert(Conversion input) {
		if (!valid(TENANT, input.tenantRef())
			|| !valid(STUDENT, input.studentRef())
			|| !valid(PARENT, input.parentRef())
			|| !valid(CLASS, input.classRef())) {
			// class_ref 없는 상담에 더미 cl_ 값을 만들지 않는다. 규약 확정 전 미전송이다.
			return Optional.empty();
		}
		String masked = textMasker.mask(input.maskedContent(), input.realNames());
		if (masked.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new CounselDraftPayload(
			input.tenantRef(), input.studentRef(), input.parentRef(), input.classRef(), masked));
	}

	private static boolean valid(Pattern pattern, String value) {
		return value != null && pattern.matcher(value).matches();
	}

	public record Conversion(
		String tenantRef,
		String studentRef,
		String parentRef,
		String classRef,
		String maskedContent,
		List<String> realNames
	) {
	}
}
