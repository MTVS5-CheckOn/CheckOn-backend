package com.checkon.member.consultation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.checkon.member.consultation.application.ConsultationAiOutcomeMapper;
import com.checkon.member.consultation.application.ConsultationTextMasker;
import com.checkon.member.consultation.domain.ConsultationAiStatus;
import com.checkon.member.integration.counsel.CounselAiAdapter;
import com.checkon.member.integration.counsel.CounselAiAdapter.Conversion;

class CounselAiAdapterTest {

	private static final String HEX = "0123456789abcdef0123456789abcdef";

	private final CounselAiAdapter adapter = new CounselAiAdapter(new ConsultationTextMasker());

	@Test
	void classRef가_없으면_더미_alias를_만들지_않고_변환을_거부한다() {
		Conversion input = new Conversion(
			"tn_" + HEX, "st_" + HEX, "pa_" + HEX, null, "상담", List.of());

		assertThat(adapter.convert(input)).isEmpty();
	}

	@Test
	void 실명과_연락처를_다시_마스킹한_값만_payload에_넣는다() {
		Conversion input = new Conversion(
			"tn_" + HEX, "st_" + HEX, "pa_" + HEX, "cl_" + HEX,
			"김학생 010-1234-5678 student@example.com", List.of("김학생"));

		String masked = adapter.convert(input).orElseThrow().textMasked();
		assertThat(masked).doesNotContain("김학생", "010-1234-5678", "student@example.com");
	}

	@Test
	void adapter_결과는_정상과_실패를_AI_상태로_안전하게_매핑한다() {
		assertThat(ConsultationAiOutcomeMapper.map("generated"))
			.isEqualTo(ConsultationAiStatus.READY);
		assertThat(ConsultationAiOutcomeMapper.map("template_only"))
			.isEqualTo(ConsultationAiStatus.TEMPLATE_ONLY);
		assertThat(ConsultationAiOutcomeMapper.map("rejected_insufficient"))
			.isEqualTo(ConsultationAiStatus.REJECTED_INSUFFICIENT);
		assertThat(ConsultationAiOutcomeMapper.map("unknown"))
			.isEqualTo(ConsultationAiStatus.UNAVAILABLE);
	}
}
