package com.checkon.publication.domain;

/**
 * 배치 한 회의 결과. 🔴 <b>실패를 삼키지 않기 위한 그릇</b>이다 — 무엇이 몇 건이었는지
 * 숫자로 남지 않으면 「돌았는데 아무 일도 없었다」와 「전부 실패했다」가 구분되지 않는다.
 *
 * @param published 새로 발행한 보고서 수
 * @param skipped   🔴 이미 우리 원장에 있어 건너뛴 수. <b>정상이다</b> — 멱등의 증거다
 * @param empty     🔴 발행할 섹션이 하나도 없어 <b>발행하지 않은</b> 수.
 *                  빈 보고서를 학부모에게 보내지 않는다
 * @param failed    예외로 실패한 수
 * @param remaining 🔴 상한에 걸려 <b>남긴</b> 수. 절대 규칙 6 — 무엇을 왜 잘랐는지 남긴다
 */
public record PublicationOutcome(
	int published, int skipped, int empty, int failed, int remaining
) {

	public static PublicationOutcome none() {
		return new PublicationOutcome(0, 0, 0, 0, 0);
	}

	public PublicationOutcome plus(PublicationOutcome other) {
		return new PublicationOutcome(
			published + other.published, skipped + other.skipped, empty + other.empty,
			failed + other.failed, remaining + other.remaining);
	}

	public PublicationOutcome withRemaining(int value) {
		return new PublicationOutcome(published, skipped, empty, failed, value);
	}

	public int handled() {
		return published + skipped + empty + failed;
	}
}
