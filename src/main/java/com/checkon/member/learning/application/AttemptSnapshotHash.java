package com.checkon.member.learning.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

import com.checkon.member.integration.problem.PublishedItemOption;
import com.checkon.member.integration.problem.PublishedItemSnapshot;

/**
 * 학습지 스냅샷의 정준 해시. attempt 시작 시 계산하고 {@code member_attempts.snapshot_hash} 에 넣는다.
 *
 * <p>정준 규칙 — 같은 입력 → 바이트 동일한 해시. 반드시 지킨다:</p>
 * <ul>
 *   <li>{@code ordinal} 오름차순으로 문항을 정렬한다.</li>
 *   <li>항목마다 {@code "itemId|ordinal|stem|passage|correctNo|explanation|options"} 순서로
 *       {@code 0x1F} (UNIT SEPARATOR) 로 이어 붙인다.</li>
 *   <li>{@code options} 는 {@code position} 오름차순으로 {@code "no:text"} 를 붙이고
 *       옵션 간에도 {@code 0x1F} 로 나눈다.</li>
 *   <li>문자열은 {@code NFC} 정규화, UTF-8 로 인코딩한다.</li>
 *   <li>🔴 {@code null} 은 빈 문자열이 아니라 {@code 0x00} 으로 구분한다 —
 *       {@code passage=""} 와 {@code passage=null} 이 같은 해시를 내면 안 된다.</li>
 * </ul>
 */
public final class AttemptSnapshotHash {

	private static final String ALGORITHM = "SHA-256";
	private static final String PREFIX = "sha256:";
	private static final char UNIT_SEPARATOR = 0x1F;
	private static final char NULL_MARKER = 0x00;
	private static final char FIELD_SEPARATOR = '|';
	private static final char OPTION_SEPARATOR = ':';

	private AttemptSnapshotHash() {
	}

	public static String compute(List<PublishedItemSnapshot> items) {
		StringBuilder buffer = new StringBuilder();
		items.stream()
			.sorted(Comparator.comparingInt(PublishedItemSnapshot::ordinal))
			.forEach(item -> appendItem(buffer, item));
		return PREFIX + hex(buffer.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void appendItem(StringBuilder buffer, PublishedItemSnapshot item) {
		appendField(buffer, item.itemId().toString());
		appendField(buffer, Integer.toString(item.ordinal()));
		appendField(buffer, item.stem());
		appendField(buffer, item.passage());
		appendField(buffer, item.correctNo() == null ? null : item.correctNo().toString());
		appendField(buffer, item.explanation());
		appendOptions(buffer, item.options());
		buffer.append(UNIT_SEPARATOR);
	}

	private static void appendField(StringBuilder buffer, String value) {
		if (value == null) {
			buffer.append(NULL_MARKER);
		}
		else {
			buffer.append(Normalizer.normalize(value, Normalizer.Form.NFC));
		}
		buffer.append(FIELD_SEPARATOR);
	}

	private static void appendOptions(StringBuilder buffer, List<PublishedItemOption> options) {
		if (options == null) {
			buffer.append(NULL_MARKER);
			return;
		}
		options.stream()
			.sorted(Comparator.comparingInt(PublishedItemOption::position))
			.forEach(option -> {
				buffer.append(option.position()).append(OPTION_SEPARATOR);
				if (option.content() == null) {
					buffer.append(NULL_MARKER);
				}
				else {
					buffer.append(Normalizer.normalize(option.content(), Normalizer.Form.NFC));
				}
				buffer.append(UNIT_SEPARATOR);
			});
	}

	private static String hex(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
			byte[] hash = digest.digest(bytes);
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte octet : hash) {
				hex.append(String.format("%02x", octet & 0xff));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 must be available", impossible);
		}
	}
}
