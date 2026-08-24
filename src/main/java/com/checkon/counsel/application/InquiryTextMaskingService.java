package com.checkon.counsel.application;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Masks PII out of a raw parent inquiry before it becomes the counsel
 * contract's {@code text_masked}. Redaction is the backend's responsibility —
 * the AI trusts that no real name or contact info survives in this field and
 * sends whatever it receives straight to the LLM (§1-④ of the counsel
 * contract).
 *
 * <p>This is pattern-based, best-effort redaction (phone numbers, resident
 * registration numbers, emails, and known real names), not general PII/NER
 * detection.
 */
@Component
public class InquiryTextMaskingService {

	private static final Pattern RESIDENT_REGISTRATION_NUMBER = Pattern.compile("\\d{6}[-\\s]?[1-4]\\d{6}");
	private static final Pattern PHONE_NUMBER = Pattern.compile("01[016789][-.\\s]?\\d{3,4}[-.\\s]?\\d{4}");
	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

	/**
	 * @param rawText the parent's original message
	 * @param knownRealNames real names on file (e.g. the student's) to redact wherever they appear verbatim
	 */
	public String mask(String rawText, List<String> knownRealNames) {
		if (rawText == null) return null;
		String masked = RESIDENT_REGISTRATION_NUMBER.matcher(rawText).replaceAll("[주민등록번호]");
		masked = PHONE_NUMBER.matcher(masked).replaceAll("[연락처]");
		masked = EMAIL.matcher(masked).replaceAll("[이메일]");
		if (knownRealNames != null) {
			for (String name : knownRealNames) {
				if (name != null && !name.isBlank()) {
					masked = masked.replace(name, "○○");
				}
			}
		}
		return masked;
	}
}
