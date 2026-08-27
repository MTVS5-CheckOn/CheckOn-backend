package com.checkon.member.consultation.application;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** 상담 원문에서 네트워크 경계를 넘을 수 없는 개인정보 패턴을 제거한다. */
@Component
public class ConsultationTextMasker {

	private static final Pattern RESIDENT_NUMBER =
		Pattern.compile("(?<!\\d)\\d{6}[- ]?[1-4]\\d{6}(?!\\d)");
	private static final Pattern PHONE =
		Pattern.compile("(?<!\\d)01[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)");
	private static final Pattern EMAIL = Pattern.compile(
		"(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?![a-z0-9])");
	private static final String MASK = "[MASKED]";

	public String mask(String content, List<String> realNames) {
		String masked = RESIDENT_NUMBER.matcher(content).replaceAll(MASK);
		masked = PHONE.matcher(masked).replaceAll(MASK);
		masked = EMAIL.matcher(masked).replaceAll(MASK);
		for (String realName : realNames) {
			if (realName != null && !realName.isBlank()) {
				masked = masked.replace(realName, MASK);
			}
		}
		return masked;
	}
}
