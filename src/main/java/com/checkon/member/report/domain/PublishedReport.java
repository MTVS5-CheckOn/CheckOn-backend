package com.checkon.member.report.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 발행된 보고서 한 건. 🔴 <b>{@code status} 필드가 없다.</b>
 *
 * <p>계약({@code member-api.yaml:2254})의 {@code status} 는 단일 enum {@code PUBLISHED} 이고,
 * 학부모 응답에 다른 값이 나갈 경로가 있으면 안 된다. 그래서 <b>DRAFT 를 담을 수 있는 타입을
 * 두지 않는다</b> — 미발행 행을 이 record 로 만들려는 코드는 컴파일은 되지만 담을 자리가 없어
 * 곧 드러난다. 리포지토리가 {@code status = 'PUBLISHED'} 행만 이 타입으로 만든다.</p>
 *
 * <p>🔴 {@code publishedAt} 이 {@code Instant} 이고 nullable 이 아닌 것도 같은 이유다.
 * V45 의 {@code ck_member_published_reports_published} 가 「PUBLISHED ⟺ published_at 있음」을
 * 강제하므로, 값이 없는 발행본은 DB 에 존재할 수 없다.</p>
 */
public record PublishedReport(
	UUID id,
	UUID studentId,
	UUID teacherId,
	String reportMonth,
	String monthZone,
	int revision,
	String snapshotVersion,
	Instant publishedAt
) {
}
