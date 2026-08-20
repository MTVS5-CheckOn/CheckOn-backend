package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("문의 텍스트 마스킹")
class InquiryTextMaskingServiceTest {

	private final InquiryTextMaskingService service = new InquiryTextMaskingService();

	@Nested
	@DisplayName("Given 연락처·주민등록번호·이메일이 섞인 문의 원문이 있을 때")
	class GivenPiiInTheRawText {

		@Test
		@DisplayName("When 마스킹하면 Then 휴대폰 번호를 가린다")
		void masksPhoneNumbers() {
			String masked = service.mask("연락은 010-1234-5678 로 부탁드려요", List.of());

			assertThat(masked).doesNotContain("010-1234-5678").contains("[연락처]");
		}

		@Test
		@DisplayName("When 마스킹하면 Then 구분자가 없는 휴대폰 번호도 가린다")
		void masksPhoneNumbersWithoutSeparators() {
			String masked = service.mask("01012345678로 연락주세요", List.of());

			assertThat(masked).doesNotContain("01012345678").contains("[연락처]");
		}

		@Test
		@DisplayName("When 마스킹하면 Then 주민등록번호를 가린다")
		void masksResidentRegistrationNumbers() {
			String masked = service.mask("아이 주민번호가 010101-3123456 이에요", List.of());

			assertThat(masked).doesNotContain("010101-3123456").contains("[주민등록번호]");
		}

		@Test
		@DisplayName("When 마스킹하면 Then 이메일을 가린다")
		void masksEmails() {
			String masked = service.mask("parent.kim@example.com 으로 자료 보내주세요", List.of());

			assertThat(masked).doesNotContain("parent.kim@example.com").contains("[이메일]");
		}
	}

	@Nested
	@DisplayName("Given 학생 실명이 문의 원문에 등장할 때")
	class GivenARealNameInTheRawText {

		@Test
		@DisplayName("When 마스킹하면 Then 등장한 실명을 전부 가린다")
		void masksKnownRealNamesEverywhereTheyAppear() {
			String masked = service.mask("김서연이 요즘 힘들어해요. 서연이 담임 선생님이세요?", List.of("김서연", "서연"));

			assertThat(masked).doesNotContain("김서연").doesNotContain("서연").contains("○○");
		}

		@Test
		@DisplayName("When 실명 목록이 비어 있으면 Then 본문을 그대로 둔다")
		void leavesTheTextUntouchedWithoutKnownNames() {
			String masked = service.mask("아이가 요즘 힘들어해요.", List.of());

			assertThat(masked).isEqualTo("아이가 요즘 힘들어해요.");
		}
	}

	@Nested
	@DisplayName("Given 원문이 null일 때")
	class GivenNullText {

		@Test
		@DisplayName("When 마스킹하면 Then null을 그대로 반환한다")
		void returnsNullForNullInput() {
			assertThat(service.mask(null, List.of("김서연"))).isNull();
		}
	}
}
