package com.checkon.member.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.report.domain.ReportFileTokenCodec;

/**
 * 분기표 §4 의 보고서·PDF 행을 1:1 로 덮는다. 정본은
 * {@code 01_endpoint_branch_matrix.md} — 표의 값 그대로 본다.
 *
 * <p>🔴 RLS 가 실제로 걸리는 역할 위에서 돈다({@link MembershipRlsEnforcedSupport}).
 * superuser 로 돌리면 정책이 통째로 우회돼(MB-34) 「남의 자녀 → 404」 같은 단언이 무엇을
 * 재는지 흐려진다.</p>
 *
 * <p>🔴 「200 이 나온다」로 끝내지 않고 몸통이 계약과 같은지도 본다. 키 부재는
 * <b>원문 body 문자열</b>로 확인한다 — {@code jsonPath().doesNotExist()} 는 값이
 * {@code null} 이어도 통과하므로 코드 규칙 §11-3 위반이다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4",
	"checkon.member.report.storage-root=build/member-report-test-storage",
	"checkon.member.report.signing-secret=test-report-signing-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReportIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String REPORTS =
		"/api/v1/member/parents/me/children/{studentId}/reports";
	private static final String REPORT = REPORTS + "/{reportId}";
	private static final String FILE_ACCESS = REPORT + "/file-access";
	private static final String DOWNLOAD = "/api/v1/member/files/reports/{token}";

	private static final Path STORAGE_ROOT = Path.of("build/member-report-test-storage");
	private static final String OBJECT_KEY = "2026-08/report.pdf";
	private static final byte[] PDF_BYTES = "%PDF-1.7 fake bytes for test".getBytes(
		StandardCharsets.UTF_8);

	private static final String MONTH = "2026-08";
	private static final String ZONE = "Asia/Seoul";
	private static final String VERSION = "rs-1";
	private static final String REASON =
		"BE 비교집단 API·원천·모수·산식 계약이 확정되지 않음";

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;
	@Autowired ReportFileTokenCodec tokenCodec;

	private JdbcTemplate admin;
	private OffsetDateTime now;
	private OffsetDateTime publishedAt;

	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID otherParentAccountId;
	private UUID otherParentProfileId;
	private UUID studentProfileId;
	private UUID otherStudentProfileId;
	private UUID unlinkedChildProfileId;
	private UUID linkedTeacherId;
	private UUID unlinkedTeacherId;

	private UUID publishedReportId;
	private UUID draftReportId;
	private UUID reviewReadyReportId;
	private UUID failedReportId;
	private UUID unlinkedTeacherReportId;
	private UUID noPdfReportId;
	private UUID fileId;

	@BeforeEach
	void setUp() throws IOException {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		now = OffsetDateTime.now(ZoneOffset.UTC);
		publishedAt = now;
		writeStoredPdf(PDF_BYTES);

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		parentProfileId = insertParent(admin, parentAccountId, "박학부모", now);
		otherParentAccountId = insertAccount(admin, "other-parent@example.com", "PARENT", now);
		otherParentProfileId = insertParent(admin, otherParentAccountId, "이학부모", now);

		// 🔴 StudentFixture 는 protected record 라 다른 패키지 서브클래스에서 생성자가
		//    막힌다(JLS 6.6.2 · MemberPostgresSupport 주석). 공개 헬퍼로 만든다.
		studentProfileId = insertChild("student@example.com", "박학생", "STU-AAAAAA");
		otherStudentProfileId = insertChild("other-student@example.com", "이학생", "STU-BBBBBB");
		unlinkedChildProfileId = insertChild("unlinked@example.com", "최학생", "STU-CCCCCC");

		linkedTeacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);
		unlinkedTeacherId = insertTeacher(admin, "other-teacher@example.com", "홍강사", now);

		link(parentProfileId, studentProfileId);
		link(otherParentProfileId, otherStudentProfileId);
		teacherLink(linkedTeacherId, studentProfileId);
		teacherLink(linkedTeacherId, otherStudentProfileId);
		// 🔴 unlinkedTeacher 는 이 자녀와 관계가 없다. 그런데도 보고서 행은 존재한다 —
		//    「행이 있어도 안 보인다」를 재려면 행이 실제로 있어야 한다.
		teacherLink(unlinkedTeacherId, unlinkedChildProfileId);

		publishedReportId = insertReport(studentProfileId, linkedTeacherId, 1, "PUBLISHED");
		draftReportId = insertReport(studentProfileId, linkedTeacherId, 2, "DRAFT");
		reviewReadyReportId = insertReport(studentProfileId, linkedTeacherId, 3, "REVIEW_READY");
		failedReportId = insertReport(studentProfileId, linkedTeacherId, 4, "FAILED");
		unlinkedTeacherReportId = insertReport(studentProfileId, unlinkedTeacherId, 1,
			"PUBLISHED");
		noPdfReportId = insertReport(otherStudentProfileId, linkedTeacherId, 1, "PUBLISHED");

		insertSection(publishedReportId, linkedTeacherId, "overall", 0, "AVAILABLE",
			"이번 달 요약", "{\"scored\":20}", null);
		insertSection(publishedReportId, linkedTeacherId, "weakness", 1, "NOT_PRODUCED",
			null, null, REASON);
		fileId = insertFile(publishedReportId, linkedTeacherId, checksumOf(PDF_BYTES));
	}

	// ══════════════════ 분기표 §4 · 목록/상세 ══════════════════

	@Test
	@DisplayName("🔴 전제 — 앱 커넥션이 RLS 대상이다 (super=f / bypassrls=f)")
	void applicationRoleIsSubjectToRowLevelSecurity() {
		assertThat(applicationRolePrivileges()).isEqualTo("false/false");
	}

	@Test
	@DisplayName("목록 — 정상 200. PUBLISHED 만, 연결된 강사 것만")
	void listReturnsOnlyPublishedOfLinkedTeacher() throws Exception {
		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].reportId").value(publishedReportId.toString()))
			.andExpect(jsonPath("$.data.items[0].status").value("PUBLISHED"))
			.andExpect(jsonPath("$.data.items[0].hasPdf").value(true))
			.andExpect(jsonPath("$.data.items[0].teacher.teacherId")
				.value(linkedTeacherId.toString()))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	@DisplayName("목록 — 발행 보고서 0건 → 200 + items:[]")
	void listWithNoPublishedReportsIsEmpty() throws Exception {
		admin.update("DELETE FROM member_report_files WHERE report_id = ?", publishedReportId);
		admin.update("DELETE FROM member_published_report_sections WHERE report_id = ?",
			publishedReportId);
		admin.update("DELETE FROM member_published_reports WHERE status = 'PUBLISHED'"
			+ " AND student_id = ?", studentProfileId);
		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(0))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	@DisplayName("🔴 상세 — DRAFT·REVIEW_READY·FAILED 는 전부 404 (존재를 숨긴다)")
	void unpublishedReportIsNotFound() throws Exception {
		for (UUID hidden : java.util.List.of(
			draftReportId, reviewReadyReportId, failedReportId)) {
			mockMvc.perform(get(REPORT, studentProfileId, hidden).with(parent()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
		}
	}

	@Test
	@DisplayName("🔴 연결 안 된 강사의 보고서 — 목록 0건, 상세 404")
	void reportOfUnlinkedTeacherIsNotFound() throws Exception {
		MvcResult listed = mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(listed.getResponse().getContentAsString())
			.as("연결 안 된 강사의 보고서가 목록에 새어 나왔다")
			.doesNotContain(unlinkedTeacherReportId.toString());

		mockMvc.perform(get(REPORT, studentProfileId, unlinkedTeacherReportId).with(parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("🔴 학부모 B 는 학부모 A 자녀의 보고서를 못 본다 → 404")
	void otherParentCannotRead() throws Exception {
		mockMvc.perform(get(REPORT, studentProfileId, publishedReportId).with(otherParent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
		mockMvc.perform(get(REPORTS, studentProfileId).with(otherParent()))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("연결 안 된 자녀 → 404 (자녀 관계 검증이 먼저)")
	void unlinkedChildIsNotFound() throws Exception {
		mockMvc.perform(get(REPORTS, unlinkedChildProfileId).with(parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("🔴 상세 — NOT_PRODUCED 섹션은 unproducedReason 을 함께 준다")
	void notProducedSectionCarriesReason() throws Exception {
		mockMvc.perform(get(REPORT, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.snapshotVersion").value(VERSION))
			.andExpect(jsonPath("$.data.sections.length()").value(2))
			.andExpect(jsonPath("$.data.sections[0].kind").value("overall"))
			.andExpect(jsonPath("$.data.sections[0].status").value("AVAILABLE"))
			.andExpect(jsonPath("$.data.sections[0].data.scored").value(20))
			.andExpect(jsonPath("$.data.sections[1].kind").value("weakness"))
			.andExpect(jsonPath("$.data.sections[1].status").value("NOT_PRODUCED"))
			.andExpect(jsonPath("$.data.sections[1].unproducedReason").value(REASON));
	}

	@Test
	@DisplayName("🔴 상세 응답 어디에도 percentile 이 없다 — 전국 백분위는 만들지 않았다")
	void noNationalPercentileField() throws Exception {
		MvcResult result = mockMvc.perform(
			get(REPORT, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isOk()).andReturn();
		assertThat(result.getResponse().getContentAsString())
			.doesNotContain("percentile", "Percentile");
	}

	@Test
	@DisplayName("목록 — PDF 없는 보고서는 hasPdf:false")
	void reportWithoutPdfReportsHasPdfFalse() throws Exception {
		mockMvc.perform(get(REPORTS, otherStudentProfileId).with(otherParent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].reportId").value(noPdfReportId.toString()))
			.andExpect(jsonPath("$.data.items[0].hasPdf").value(false));
	}

	@Test
	@DisplayName("🔴 limit 상한 초과 → 400. 조용히 깎지 않는다")
	void limitOverMaxIsRejected() throws Exception {
		mockMvc.perform(get(REPORTS, studentProfileId).param("limit", "51").with(parent()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		mockMvc.perform(get(REPORTS, studentProfileId).param("limit", "0").with(parent()))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("cursor 위조 → 400")
	void malformedCursorIsRejected() throws Exception {
		mockMvc.perform(get(REPORTS, studentProfileId).param("cursor", "!!!").with(parent()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	// ══════════════════ 분기표 §4 · file-access ══════════════════

	@Test
	@DisplayName("file-access — 정상 201 + 계약 6필드")
	void fileAccessIssuesSignedUrl() throws Exception {
		mockMvc.perform(post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.url").exists())
			.andExpect(jsonPath("$.data.expiresAt").exists())
			.andExpect(jsonPath("$.data.contentType").value("application/pdf"))
			.andExpect(jsonPath("$.data.checksum").value(checksumOf(PDF_BYTES)))
			.andExpect(jsonPath("$.data.sizeBytes").value(PDF_BYTES.length));
	}

	@Test
	@DisplayName("🔴 file-access — pageCount 원본이 없으면 키를 두고 값은 null (0 아님)")
	void pageCountStaysNullWhenUnmeasured() throws Exception {
		MvcResult result = mockMvc.perform(
			post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isCreated()).andReturn();
		// 🔴 jsonPath doesNotExist 는 값이 null 이어도 통과한다 — 원문으로 본다(§11-3).
		assertThat(result.getResponse().getContentAsString())
			.contains("\"pageCount\":null");
	}

	@Test
	@DisplayName("🔴 file-access·상세 응답 어디에도 object key 가 없다")
	void responseNeverContainsObjectKey() throws Exception {
		MvcResult access = mockMvc.perform(
			post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isCreated()).andReturn();
		MvcResult detail = mockMvc.perform(
			get(REPORT, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isOk()).andReturn();

		for (String body : java.util.List.of(
			access.getResponse().getContentAsString(),
			detail.getResponse().getContentAsString())) {
			assertThat(body).doesNotContain("objectKey", "object_key", "bucket", OBJECT_KEY);
		}
	}

	@Test
	@DisplayName("file-access — PDF 미생성(파일 행 없음) → 404")
	void fileAccessWithoutFileRowIsNotFound() throws Exception {
		mockMvc.perform(post(FILE_ACCESS, otherStudentProfileId, noPdfReportId)
			.with(otherParent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("file-access — 미발행 보고서 → 404")
	void fileAccessForUnpublishedIsNotFound() throws Exception {
		mockMvc.perform(post(FILE_ACCESS, studentProfileId, draftReportId).with(parent()))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("🔴 file-access — 남의 자녀 보고서에는 URL 을 발급하지 않는다 → 404")
	void fileAccessForOtherParentIsNotFound() throws Exception {
		mockMvc.perform(post(FILE_ACCESS, studentProfileId, publishedReportId)
			.with(otherParent()))
			.andExpect(status().isNotFound());
	}

	/**
	 * 🔴 <b>변조 바이트는 원본과 길이가 같아야 한다.</b> 처음엔 길이가 다른 문자열을 썼는데,
	 * 그러면 checksum 이 아니라 <b>크기 검사</b>가 먼저 잡는다 — 고의 파괴 #7 로 실측했다
	 * (checksum 비교를 통째로 무력화해도 이 테스트가 green 이었다). 회귀 문서 §3-10
	 * 「red 를 누가 냈는가」의 그 자리다. 크기 불일치는 {@link #sizeMismatchBlocksIssue} 가 따로 잰다.
	 */
	@Test
	@DisplayName("🔴 checksum 불일치 → 503. url 을 주지 않는다 (크기는 같다)")
	void checksumMismatchBlocksIssue() throws Exception {
		byte[] tampered = "%PDF-1.7 fake bytes for tesT".getBytes(StandardCharsets.UTF_8);
		assertThat(tampered).as("크기가 다르면 크기 검사가 먼저 잡아 이 테스트가 헛돈다")
			.hasSameSizeAs(PDF_BYTES);
		writeStoredPdf(tampered);
		MvcResult result = mockMvc.perform(
			post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("\"url\"");
	}

	@Test
	@DisplayName("🔴 저장된 크기가 다르면 → 503 (checksum 계산 전에 걸린다)")
	void sizeMismatchBlocksIssue() throws Exception {
		writeStoredPdf("%PDF-1.7 different length entirely".getBytes(StandardCharsets.UTF_8));
		mockMvc.perform(post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
	}

	@Test
	@DisplayName("🔴 저장소가 보고한 content type 이 다르면 → 503")
	void contentTypeMismatchBlocksIssue() throws Exception {
		// 🔴 로컬 어댑터는 키 확장자로 content type 을 결정한다(OS 무관). .bin 키를 쓰면
		//    저장소는 application/octet-stream 을 보고하고 DB 는 application/pdf 라 불일치다.
		//    content_type 컬럼은 CHECK 로 application/pdf 만 허용되므로 값을 바꿀 수 없다 —
		//    「키가 주장하는 형식」과 「DB 가 적은 형식」이 갈리는 상황이 바로 이 분기다.
		UUID otherReport = insertReport(studentProfileId, linkedTeacherId, 20, "PUBLISHED");
		byte[] bytes = "%PDF-1.7 stored under a bin key".getBytes(StandardCharsets.UTF_8);
		writeStoredObject("2026-08/report.bin", bytes);
		admin.update("INSERT INTO member_report_files (id, report_id, student_id, teacher_id,"
			+ " published_at, object_key, checksum, content_type, size_bytes, page_count,"
			+ " created_at) VALUES (?, ?, ?, ?, ?, '2026-08/report.bin', ?,"
			+ " 'application/pdf', ?, NULL, ?)",
			UUID.randomUUID(), otherReport, studentProfileId, linkedTeacherId, publishedAt,
			checksumOf(bytes), (long) bytes.length, now);

		mockMvc.perform(post(FILE_ACCESS, studentProfileId, otherReport).with(parent()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
	}

	@Test
	@DisplayName("🔴 file-access — 관계가 끝난 뒤에는 발급하지 않는다 → 404")
	void fileAccessAfterRelationshipEndsIsNotFound() throws Exception {
		mockMvc.perform(post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isCreated());
		admin.update("UPDATE parent_student_relationships SET status = 'ENDED', ended_at = ?"
			+ " WHERE parent_id = ? AND student_id = ?", now, parentProfileId, studentProfileId);
		mockMvc.perform(post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("🔴 teacherId 필터가 연결 안 된 강사 → 200 + items:[] (404 아님)")
	void teacherFilterOutsideAllowedSetIsEmpty() throws Exception {
		mockMvc.perform(get(REPORTS, studentProfileId)
			.param("teacherId", unlinkedTeacherId.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(0))
			.andExpect(jsonPath("$.data.hasNext").value(false));
		// 연결된 강사로 거르면 그대로 나온다 — 필터가 통째로 죽은 게 아니다.
		mockMvc.perform(get(REPORTS, studentProfileId)
			.param("teacherId", linkedTeacherId.toString()).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1));
	}

	@Test
	@DisplayName("🔴 저장소에 파일이 없으면 → 503 (URL 을 주지 않는다)")
	void missingObjectBlocksIssue() throws Exception {
		Files.deleteIfExists(STORAGE_ROOT.resolve(OBJECT_KEY));
		mockMvc.perform(post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isServiceUnavailable());
	}

	// ══════════════════ 분기표 §4 · 공개 다운로드 ══════════════════

	@Test
	@DisplayName("다운로드 — 정상 200 application/pdf + inline Content-Disposition")
	void downloadReturnsPdf() throws Exception {
		String token = issuedToken();
		MvcResult result = mockMvc.perform(get(DOWNLOAD, token))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "application/pdf"))
			.andReturn();
		assertThat(result.getResponse().getHeader("Content-Disposition"))
			.contains("inline")
			.contains("report-" + MONTH + "-r1.pdf")
			// 🔴 파일명에 실명·별칭·공개 학생 ID 를 넣지 않는다.
			.doesNotContain("박학생", "STU-AAAAAA");
		assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PDF_BYTES);
	}

	@Test
	@DisplayName("🔴 다운로드 — 만료된 토큰 → 404 (Thread.sleep 없이 만료 시각으로)")
	void signedUrlRejectedAfterExpiry() throws Exception {
		String expired = tokenCodec.issue(fileId, parentProfileId,
			Instant.now().minusSeconds(1));
		mockMvc.perform(get(DOWNLOAD, expired))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("🔴 다운로드 — 서명 변조 토큰 → 404")
	void tamperedTokenIsRejected() throws Exception {
		String token = issuedToken();
		String tampered = token.substring(0, token.length() - 2) + "AA";
		mockMvc.perform(get(DOWNLOAD, tampered)).andExpect(status().isNotFound());
		mockMvc.perform(get(DOWNLOAD, "not-a-token")).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("🔴 다운로드 — 남의 보고서 토큰으로는 못 받는다 → 404")
	void foreignParentTokenCannotDownload() throws Exception {
		// 서명은 진짜다. 학부모만 남이다 — 관계 재검증이 유일한 방어선이 되는 자리다.
		String forged = tokenCodec.issue(fileId, otherParentProfileId,
			Instant.now().plusSeconds(300));
		mockMvc.perform(get(DOWNLOAD, forged))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("🔴 관계가 끊기면 이미 발급된 URL 도 그 순간부터 404")
	void accessRevokedAfterRelationshipEnds() throws Exception {
		String token = issuedToken();
		mockMvc.perform(get(DOWNLOAD, token)).andExpect(status().isOk());

		// 🔴 기존 CHECK(ck_parent_student_relationships_status_time)가 ENDED 에 ended_at 을
		//    요구한다. 상태만 바꾸면 제약 위반이라 테스트가 무엇을 재는지 흐려진다.
		admin.update("UPDATE parent_student_relationships SET status = 'ENDED', ended_at = ?"
			+ " WHERE parent_id = ? AND student_id = ?", now, parentProfileId, studentProfileId);

		mockMvc.perform(get(DOWNLOAD, token)).andExpect(status().isNotFound());
		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isNotFound());
		mockMvc.perform(get(REPORT, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("🔴 다운로드 — 저장소 장애면 503, 바이트는 0")
	void downloadFailsClosedWhenStorageIsGone() throws Exception {
		String token = issuedToken();
		Files.deleteIfExists(STORAGE_ROOT.resolve(OBJECT_KEY));
		MvcResult result = mockMvc.perform(get(DOWNLOAD, token))
			.andExpect(status().isServiceUnavailable()).andReturn();
		// 🔴 byte[] 의 doesNotContain 은 「이 값들 중 아무것도 없다」라 부분열 판정이 아니다.
		//    실제로 오탐이 났다 — 문자열로 본다.
		assertThat(result.getResponse().getContentAsString())
			.as("실패했는데 PDF 바이트가 나갔다")
			.doesNotContain(new String(PDF_BYTES, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("🔴 다운로드 경로는 세션 없이 통과한다 — member 체인이 실제로 permitAll 한다")
	void downloadPathIsPublicInMemberChain() throws Exception {
		// 인증 postprocessor 없이 요청한다. 401 이면 체인이 이 경로를 안 잡은 것이다.
		mockMvc.perform(get(DOWNLOAD, issuedToken())).andExpect(status().isOk());
		// 🔴 그리고 「전부 통과」가 아니다 — 형식이 맞는 아무 토큰이나 404 여야 한다.
		mockMvc.perform(get(DOWNLOAD, "aaaa.bbbb")).andExpect(status().isNotFound());
	}

	// ══════════════════ 발행 알림 ══════════════════

	@Test
	@DisplayName("알림 — 목록 조회 두 번에도 알림은 1행, outbox 는 DONE")
	void notificationPublishedOnceOnDrain() throws Exception {
		insertOutbox(publishedReportId, linkedTeacherId);

		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk());
		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk());

		assertThat(countNotifications()).isEqualTo(1);
		assertThat(admin.queryForObject("SELECT status FROM"
			+ " member_report_publication_outbox WHERE report_id = ?", String.class,
			publishedReportId)).isEqualTo("DONE");
		assertThat(admin.queryForObject("SELECT type FROM member_notifications"
			+ " WHERE source_id = ?", String.class, publishedReportId))
			.isEqualTo("REPORT_PUBLISHED");
	}

	@Test
	@DisplayName("🔴 알림 제목에 점수·실명이 없다")
	void notificationTitleCarriesNoScoreOrName() throws Exception {
		insertOutbox(publishedReportId, linkedTeacherId);
		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk());

		String title = admin.queryForObject("SELECT title FROM member_notifications"
			+ " WHERE source_id = ?", String.class, publishedReportId);
		assertThat(title).isEqualTo("8월 보고서가 발행되었습니다")
			.doesNotContain("박학생", "STU-AAAAAA", "20", "%");
		assertThat(admin.queryForObject("SELECT count(*) FROM member_notifications"
			+ " WHERE body IS NOT NULL", Integer.class)).isZero();
	}

	@Test
	@DisplayName("🔴 drain 상한을 넘긴 건은 PENDING 으로 남는다")
	void drainCapLeavesPending() throws Exception {
		// 상한은 5. 서로 다른 보고서 7건을 발행하고 outbox 를 7건 만든다.
		for (int revision = 10; revision < 17; revision++) {
			UUID reportId = insertReport(studentProfileId, linkedTeacherId, revision,
				"PUBLISHED");
			insertOutbox(reportId, linkedTeacherId);
		}
		assertThat(pendingCount()).isEqualTo(7);

		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk());

		assertThat(countNotifications()).isEqualTo(5);
		assertThat(pendingCount()).as("상한 초과분이 조용히 사라졌다").isEqualTo(2);
	}

	// ══════════════════ 실 AI 호출 0회 ══════════════════

	/**
	 * 🔴 「AI 를 안 불렀다」를 로그나 호출 횟수로 확인하면 못 잡는다 — 안 부른 실행 하나를
	 * 봤을 뿐이다. <b>부를 수단이 이 경계의 의존 그래프에 없다</b>를 본다.
	 *
	 * <p>member 경계에서 AI 를 부르는 타입은 {@code CounselAiAdapter} 하나다(PR8).
	 * report 의 어떤 빈도 그것을 생성자로 받지 않는다는 것이 이 단언이다.</p>
	 *
	 * <p>🔴 이 판정의 한계를 적어 둔다 — <b>생성자 한 단계</b>만 본다. 중간 빈을 하나 끼워
	 * 우회하면 못 잡는다. 그 층은 G2(패키지 경계)가 소스에서 막는다.</p>
	 */
	@Test
	@DisplayName("🔴 report 경계의 어떤 빈도 AI 어댑터를 생성자로 받지 않는다")
	void noAiClientParticipatesInReportPaths() {
		java.util.List<String> offenders = new java.util.ArrayList<>();
		for (String name : reportBeanNames()) {
			Class<?> type = applicationContext.getType(name);
			if (type == null) {
				continue;
			}
			for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
				for (Class<?> parameter : constructor.getParameterTypes()) {
					if (parameter.getName().startsWith("com.checkon.member.integration.counsel")) {
						offenders.add(name + " <- " + parameter.getSimpleName());
					}
				}
			}
		}
		assertThat(offenders).as("report 경계에 AI 어댑터가 주입됐다").isEmpty();
		// 🔴 판정이 헛돌지 않는지 확인한다 — 볼 빈이 하나도 없으면 위 단언은 무의미하다.
		assertThat(reportBeanNames()).as("report 빈을 하나도 못 찾았다면 필터가 죽은 것이다")
			.contains("parentReportQueryService", "reportFileAccessService");
	}

	// ──────────────────────────── 도우미 ────────────────────────────

	@Autowired org.springframework.context.ApplicationContext applicationContext;

	private java.util.List<String> reportBeanNames() {
		return java.util.Arrays.stream(applicationContext.getBeanDefinitionNames())
			.filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("report"))
			.toList();
	}

	private String issuedToken() throws Exception {
		MvcResult result = mockMvc.perform(
			post(FILE_ACCESS, studentProfileId, publishedReportId).with(parent()))
			.andExpect(status().isCreated()).andReturn();
		String body = result.getResponse().getContentAsString();
		int start = body.indexOf("\"url\":\"") + 7;
		int end = body.indexOf('"', start);
		String url = body.substring(start, end);
		return url.substring(url.lastIndexOf('/') + 1);
	}

	private static void writeStoredPdf(byte[] bytes) throws IOException {
		writeStoredObject(OBJECT_KEY, bytes);
	}

	private static void writeStoredObject(String objectKey, byte[] bytes) throws IOException {
		Path file = STORAGE_ROOT.resolve(objectKey);
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static String checksumOf(byte[] bytes) {
		try {
			return "sha256:" + HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	private int countNotifications() {
		Integer count = admin.queryForObject(
			"SELECT count(*) FROM member_notifications WHERE type = 'REPORT_PUBLISHED'",
			Integer.class);
		return count == null ? 0 : count;
	}

	private int pendingCount() {
		Integer count = admin.queryForObject("SELECT count(*) FROM"
			+ " member_report_publication_outbox WHERE status = 'PENDING'", Integer.class);
		return count == null ? 0 : count;
	}

	/** 학생 프로필 + 표시 이름 + 공개 ID 한 벌. 활성화 행은 이 경계가 안 읽으므로 만들지 않는다. */
	private UUID insertChild(String email, String displayName, String publicId) {
		UUID accountId = insertAccount(admin, email, "STUDENT", now);
		UUID profileId = com.checkon.member.support.MemberPostgresSupport.insertStudentProfile(
			admin, accountId, displayName, 2, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?)", accountId, displayName, now, now);
		admin.update("INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
			+ " VALUES (?, ?, ?)", profileId, publicId, now);
		return profileId;
	}

	private void link(UUID parentId, UUID studentId) {
		admin.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentId, studentId, now, now);
	}

	private void teacherLink(UUID teacherId, UUID studentId) {
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentId, now, now);
	}

	private UUID insertReport(UUID studentId, UUID teacherId, int revision, String status) {
		UUID id = UUID.randomUUID();
		OffsetDateTime published = "PUBLISHED".equals(status) ? publishedAt : null;
		String failureReason = "FAILED".equals(status) ? "renderer crashed" : null;
		admin.update("INSERT INTO member_published_reports (id, student_id, teacher_id,"
			+ " report_month, month_zone, revision, status, snapshot_version, created_at,"
			+ " updated_at, published_at, failure_reason)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			id, studentId, teacherId, MONTH, ZONE, revision, status, VERSION,
			now, now, published, failureReason);
		return id;
	}

	private void insertSection(
		UUID reportId, UUID teacherId, String kind, int ordinal, String status,
		String body, String content, String unproducedReason
	) {
		admin.update("INSERT INTO member_published_report_sections (id, report_id, student_id,"
			+ " teacher_id, published_at, kind, title, ordinal, status, body, content,"
			+ " evidence_refs, unproduced_reason, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?::jsonb, '[]'::jsonb, ?, ?)",
			UUID.randomUUID(), reportId, studentProfileId, teacherId, publishedAt, kind,
			ordinal, status, body, content, unproducedReason, now);
	}

	private UUID insertFile(UUID reportId, UUID teacherId, String checksum) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO member_report_files (id, report_id, student_id, teacher_id,"
			+ " published_at, object_key, checksum, content_type, size_bytes, page_count,"
			+ " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'application/pdf', ?, NULL, ?)",
			id, reportId, studentProfileId, teacherId, publishedAt, OBJECT_KEY, checksum,
			(long) PDF_BYTES.length, now);
		return id;
	}

	private void insertOutbox(UUID reportId, UUID teacherId) {
		admin.update("INSERT INTO member_report_publication_outbox (id, report_id, student_id,"
			+ " teacher_id, published_at, status, created_at)"
			+ " VALUES (?, ?, ?, ?, ?, 'PENDING', ?)",
			UUID.randomUUID(), reportId, studentProfileId, teacherId, publishedAt, now);
	}

	private RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}

	private RequestPostProcessor otherParent() {
		return authentication(principalOf(otherParentAccountId, AccountRole.PARENT));
	}
}
