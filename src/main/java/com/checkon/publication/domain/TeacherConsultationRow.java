package com.checkon.publication.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 강사가 보는 상담 한 건. {@code member_consultations} 와 1:1.
 *
 * @param content     🔴 <b>학부모가 쓴 원문</b>이다. 강사가 답을 쓰려면 봐야 한다 —
 *                    그것이 이 API 의 목적이다. 🔴 <b>로그에 넣지 마라</b>
 * @param cancelledAt 취소 시각. 🔴 취소 기능은 MB-09 로 아직 없지만 컬럼은 있다 —
 *                    값이 있으면 답할 수 없다. 방어는 지금 넣는다
 */
public record TeacherConsultationRow(
	UUID consultationId,
	UUID parentId,
	UUID studentId,
	UUID teacherId,
	String status,
	String aiStatus,
	String topic,
	String urgency,
	String content,
	Instant createdAt,
	Instant updatedAt,
	Instant answeredAt,
	Instant cancelledAt
) {

	/**
	 * 답변을 받을 수 있는 상태인가. 🔴 <b>fail-closed</b> — 상태가 목록에 없거나 취소됐으면
	 * 답할 수 없다. 답을 여는 것은 나중에 쉽지만 보낸 답은 못 되돌린다.
	 */
	public boolean answerable() {
		return cancelledAt == null && ConsultationStatus.ANSWERABLE.contains(status);
	}
}
