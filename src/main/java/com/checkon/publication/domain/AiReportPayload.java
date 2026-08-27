package com.checkon.publication.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * {@code monthly_reports.ai_payload}(JSONB) 를 학부모 원장 섹션으로 옮긴다. <b>순수 함수다</b> —
 * 스프링도 DB 도 모른다.
 *
 * <p>🔴 <b>실측한 구조</b>(2026-08-27). {@code MonthlyReportResultListener} 가 Kafka 결과
 * 이벤트의 {@code ai_response} 노드를 통째로 저장하고, 그 모양은 {@code CheckOn-AI} 의
 * {@code src/ai/contracts/report.py}({@code ReportBlock} · {@code ReportBlockRevision}) 와
 * {@code src/ai/api/routers/report.py:298-315} 이 정한다:</p>
 *
 * <pre>
 * { "data": {
 *     "status": "ready" | "rejected_insufficient" | "template_only",
 *     "blocks": [ { "block_id", "seq", "block_type", "active_revision_no",
 *                   "revisions": [ { "revision_no", "revision_kind",
 *                                    "ai_original", "teacher_edit",
 *                                    "evidence": [ {"source_table","record_id","summary"} ] } ] } ],
 *     "unproduced_sections": [ { "key", "status", "reason" } ]   // 있을 수도, 없을 수도
 * } }
 * </pre>
 *
 * <p>🔴 <b>{@code unproduced_sections} 는 있을 수도 없을 수도 있다.</b> HTTP 상세 응답에는
 * 있지만({@code report.py:307}) Kafka 결과 이벤트에 실리는지는 저장소 어디에도 예시가 없어
 * 확인하지 못했다. 그래서 <b>있으면 읽고 없으면 만들지 않는다</b> — 없는 섹션을 지어내는
 * 것보다 없는 채로 두는 쪽이 정직하다.</p>
 *
 * <p>🔴 <b>본문은 활성 리비전에서 온다</b> — {@code teacher_edit} 이 있으면 그것이고
 * 없으면 {@code ai_original} 이다. 강사가 고친 문장이 있는데 AI 원문을 학부모에게 보내면
 * 검토를 무시하는 것이다.</p>
 *
 * <p>🔴 <b>같은 {@code block_type} 이 여러 번 나오면 하나로 합친다.</b> V45 의
 * {@code UNIQUE (report_id, kind)} 가 한 종류당 한 섹션만 허용하기 때문이다. 합치는 규칙은
 * 「{@code seq} 오름차순으로 본문을 빈 줄로 잇는다」 하나뿐이고 {@code ordinal} 은 그 종류의
 * 최소 {@code seq} 다. 🔴 대안으로 {@code fact-2} 같은 kind 를 만들 수도 있었지만 그건
 * <b>없는 어휘를 지어내는 것</b>이라 택하지 않았다.</p>
 */
public final class AiReportPayload {

	private static final String READY = "ready";
	private static final String REJECTED_INSUFFICIENT = "rejected_insufficient";
	private static final String TEMPLATE_ONLY = "template_only";

	private AiReportPayload() {
	}

	/**
	 * @param aiPayload {@code ai_payload} 루트. {@code null} 이면 빈 목록이다
	 * @return 섹션 목록. 🔴 비어 있으면 <b>발행할 내용이 없다</b>는 뜻이고 호출자가 그 판단을 한다
	 */
	public static List<PublishableSection> toSections(JsonNode aiPayload) {
		if (aiPayload == null || aiPayload.isMissingNode() || aiPayload.isNull()) {
			return List.of();
		}
		JsonNode data = aiPayload.path("data");
		String status = sectionStatusFor(data.path("status").asString(""));
		List<PublishableSection> sections = new ArrayList<>(blockSections(data, status));
		sections.addAll(unproducedSections(data, sections.size()));
		return List.copyOf(sections);
	}

	/**
	 * 🔴 AI 어휘 3종을 V45 의 {@code status} 4종으로 옮긴다. 어느 쪽 어휘도 늘리지 않는다.
	 *
	 * <ul>
	 *   <li>{@code ready} → {@code AVAILABLE}</li>
	 *   <li>{@code rejected_insufficient} → {@code INSUFFICIENT} — AI 가 「데이터가 모자라
	 *       거절했다」는 뜻이라 그대로 옮긴다</li>
	 *   <li>{@code template_only} → 🔴 <b>{@code INSUFFICIENT}</b>. 본문은 있지만 실제 데이터가
	 *       아니라 템플릿 문구다 — {@code AVAILABLE} 로 두면 「측정한 값」이라는 거짓말이 된다</li>
	 *   <li>그 밖 / 없음 → {@code INSUFFICIENT}. 🔴 모르는 값을 {@code AVAILABLE} 로 올리지
	 *       않는다. fail-closed 다</li>
	 * </ul>
	 */
	static String sectionStatusFor(String aiStatus) {
		return switch (aiStatus) {
			case READY -> PublishableSection.AVAILABLE;
			case REJECTED_INSUFFICIENT, TEMPLATE_ONLY -> PublishableSection.INSUFFICIENT;
			default -> PublishableSection.INSUFFICIENT;
		};
	}

	private static List<PublishableSection> blockSections(JsonNode data, String status) {
		Map<String, Merged> merged = new LinkedHashMap<>();
		for (JsonNode block : data.path("blocks")) {
			String kind = block.path("block_type").asString("");
			String body = activeBody(block);
			if (kind.isBlank() || body == null || body.isBlank()) {
				// 🔴 본문을 못 찾은 블록은 조용히 버리지 않고 건너뛴다 — 아래에서 섹션이 하나도
				//    안 남으면 호출자가 「발행할 내용 없음」으로 판정한다.
				continue;
			}
			int seq = block.path("seq").asInt(0);
			merged.computeIfAbsent(kind, ignored -> new Merged()).add(seq, body, evidenceOf(block));
		}
		List<PublishableSection> sections = new ArrayList<>();
		int ordinal = 0;
		for (Map.Entry<String, Merged> entry : merged.entrySet()) {
			Merged value = entry.getValue();
			sections.add(PublishableSection.ofText(
				entry.getKey(), ordinal++, status, value.body(), List.copyOf(value.evidence)));
		}
		return sections;
	}

	/** 🔴 {@code teacher_edit} 우선. 강사가 고친 문장이 있으면 그것이 학부모에게 간다. */
	private static String activeBody(JsonNode block) {
		int active = block.path("active_revision_no").asInt(-1);
		JsonNode chosen = null;
		for (JsonNode revision : block.path("revisions")) {
			if (revision.path("revision_no").asInt(-2) == active) {
				chosen = revision;
				break;
			}
		}
		if (chosen == null) {
			return null;
		}
		String edited = chosen.path("teacher_edit").asString("");
		return edited.isBlank() ? nullIfBlank(chosen.path("ai_original").asString("")) : edited;
	}

	/**
	 * 🔴 계약의 {@code evidenceRefs} 는 <b>문자열 배열</b>이고 AI 의 evidence 는 객체다.
	 * {@code source_table:record_id} 로 옮긴다 — 그것이 「어느 원본의 어느 행인가」라는
	 * 논리 참조다. {@code summary} 는 사람이 읽는 설명이라 참조에 섞지 않는다.
	 */
	private static List<String> evidenceOf(JsonNode block) {
		List<String> refs = new ArrayList<>();
		for (JsonNode revision : block.path("revisions")) {
			for (JsonNode evidence : revision.path("evidence")) {
				String table = evidence.path("source_table").asString("");
				String record = evidence.path("record_id").asString("");
				if (!table.isBlank() && !record.isBlank()) {
					refs.add(table + ":" + record);
				}
			}
		}
		return refs;
	}

	/** 🔴 있으면 읽고 없으면 만들지 않는다. 사유는 AI 문자열 그대로 옮긴다. */
	private static List<PublishableSection> unproducedSections(JsonNode data, int firstOrdinal) {
		List<PublishableSection> sections = new ArrayList<>();
		int ordinal = firstOrdinal;
		for (JsonNode item : data.path("unproduced_sections")) {
			String key = item.path("key").asString("");
			String reason = item.path("reason").asString("");
			if (key.isBlank() || reason.isBlank()) {
				continue;
			}
			sections.add(PublishableSection.notProduced(key, ordinal++, reason));
		}
		return sections;
	}

	private static String nullIfBlank(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/** 같은 {@code block_type} 의 본문·근거를 {@code seq} 순서로 모은다. */
	private static final class Merged {

		private final Map<Integer, String> bodies = new java.util.TreeMap<>();
		private final Set<String> evidence = new LinkedHashSet<>();

		private void add(int seq, String body, List<String> refs) {
			bodies.put(seq, body);
			evidence.addAll(refs);
		}

		private String body() {
			return String.join("\n\n", bodies.values());
		}
	}
}
