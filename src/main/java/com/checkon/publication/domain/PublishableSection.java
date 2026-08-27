package com.checkon.publication.domain;

import java.util.List;

/**
 * 학부모 원장에 넣을 섹션 하나. V45 {@code member_published_report_sections} 와 1:1.
 *
 * <p>🔴 {@code kind} 에 어휘를 만들지 않는다. 값은 AI 블록의 {@code block_type} 을 그대로
 * 쓰거나(5종: greeting·fact·chart_analysis·suggestion·closing), 미산출 항목의 {@code key} 를
 * 그대로 쓴다. V45 는 {@code kind} 에 CHECK 를 두지 않았고 계약의 {@code kind} 도 enum 이
 * 아니라서, 여기서 새 어휘를 정하면 정본이 하나 더 생긴다.</p>
 *
 * <p>🔴 <b>{@code status} 는 V45 CHECK 의 4종뿐</b>이다 —
 * {@code AVAILABLE}·{@code INSUFFICIENT}·{@code NO_DATA}·{@code NOT_PRODUCED}.
 * 그리고 DB CHECK 둘이 정직성을 강제한다:
 * {@code (status='NOT_PRODUCED') = (unproduced_reason IS NOT NULL)} 와
 * {@code status <> 'AVAILABLE' OR body IS NOT NULL OR content IS NOT NULL}.
 * 여기 생성자가 같은 판정을 <b>쓰기 전에</b> 한 번 더 한다 — DB 까지 가서 터지면
 * 트랜잭션 하나가 통째로 죽고 무엇이 잘못됐는지 로그가 흐려진다.</p>
 *
 * @param content 차트용 원시 값. 🔴 AI 블록에는 본문 텍스트만 있고 차트 원본이 따로 없다 —
 *                <b>항상 {@code null}</b> 이다. 지어내지 않는다
 */
public record PublishableSection(
	String kind,
	Integer ordinal,
	String status,
	String body,
	String content,
	List<String> evidenceRefs,
	String unproducedReason
) {

	public static final String AVAILABLE = "AVAILABLE";
	public static final String INSUFFICIENT = "INSUFFICIENT";
	public static final String NO_DATA = "NO_DATA";
	public static final String NOT_PRODUCED = "NOT_PRODUCED";

	private static final List<String> ALLOWED =
		List.of(AVAILABLE, INSUFFICIENT, NO_DATA, NOT_PRODUCED);

	public PublishableSection {
		if (kind == null || kind.isBlank()) {
			throw new IllegalArgumentException("section kind must not be blank");
		}
		if (ordinal == null || ordinal < 0) {
			throw new IllegalArgumentException("section ordinal must be >= 0: " + kind);
		}
		if (!ALLOWED.contains(status)) {
			throw new IllegalArgumentException("unknown section status: " + status);
		}
		if (NOT_PRODUCED.equals(status) != (unproducedReason != null)) {
			throw new IllegalArgumentException(
				"NOT_PRODUCED requires a reason and nothing else may carry one: " + kind);
		}
		if (AVAILABLE.equals(status) && body == null && content == null) {
			throw new IllegalArgumentException("AVAILABLE section needs body or content: " + kind);
		}
		evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
	}

	/** 본문이 있는 섹션. {@code status} 는 AI 가 낸 품질에 따라 갈린다. */
	public static PublishableSection ofText(
		String kind, int ordinal, String status, String body, List<String> evidenceRefs
	) {
		return new PublishableSection(kind, ordinal, status, body, null, evidenceRefs, null);
	}

	/** 🔴 산출되지 않은 항목. 사유는 <b>AI 가 준 문자열 그대로</b>다 — 요약하지 않는다. */
	public static PublishableSection notProduced(String kind, int ordinal, String reason) {
		return new PublishableSection(kind, ordinal, NOT_PRODUCED, null, null, List.of(), reason);
	}
}
