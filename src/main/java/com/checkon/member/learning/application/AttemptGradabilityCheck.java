package com.checkon.member.learning.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.integration.problem.PublishedItemSnapshot;

/**
 * attempt 시작 시 스냅샷이 채점 가능한지 검증한다(§3 판정 시점: 「제출이 아니라 시작」).
 *
 * <p>🔴 {@code correctNo}·{@code areaTag}·{@code typeTag}·{@code skillNodeId} 넷 중 하나라도
 * null 이면 {@code problem_assignment_responses} 의 NOT NULL 을 만족시킬 수 없어 제출 자체가
 * 불가능하다. 그래서 {@code WORKSHEET_NOT_GRADABLE}(422) 로 시작 자체를 막고 attempt 를
 * 만들지 않는다(설계 §7 · PR5 §3 재측정).</p>
 *
 * <p>🔴 문항 0개도 같은 코드를 낸다({@code missing=["items"]}) — attempt 를 만들 근거가 없다.</p>
 */
public final class AttemptGradabilityCheck {

	private AttemptGradabilityCheck() {
	}

	public static void verify(List<PublishedItemSnapshot> snapshots) {
		if (snapshots.isEmpty()) {
			throw new MemberException(MemberErrorCode.WORKSHEET_NOT_GRADABLE,
				"published worksheet has no items",
				new WorksheetNotGradableDetails(List.of(), List.of("items")));
		}
		List<UUID> offendingIds = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (PublishedItemSnapshot snapshot : snapshots) {
			List<String> gaps = collectGaps(snapshot);
			if (!gaps.isEmpty()) {
				offendingIds.add(snapshot.itemId());
				for (String gap : gaps) {
					if (!missing.contains(gap)) {
						missing.add(gap);
					}
				}
			}
		}
		if (!offendingIds.isEmpty()) {
			throw new MemberException(MemberErrorCode.WORKSHEET_NOT_GRADABLE,
				"worksheet snapshot is missing required fields",
				new WorksheetNotGradableDetails(offendingIds, missing));
		}
	}

	private static List<String> collectGaps(PublishedItemSnapshot snapshot) {
		List<String> gaps = new ArrayList<>();
		if (snapshot.correctNo() == null) {
			gaps.add("correctNo");
		}
		if (snapshot.areaTag() == null) {
			gaps.add("areaTag");
		}
		if (snapshot.typeTag() == null) {
			gaps.add("typeTag");
		}
		if (snapshot.skillNodeId() == null) {
			gaps.add("skillNodeId");
		}
		return gaps;
	}
}
