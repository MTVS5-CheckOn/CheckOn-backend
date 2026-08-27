package com.checkon.member.report;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.common.presentation.MemberRateLimiter;
import com.checkon.member.membership.MembershipRlsEnforcedSupport;
import com.checkon.member.support.MemberPostgresSupport;

/**
 * 🔴 <b>저장소가 설정되지 않은 컨텍스트</b>에서 무엇이 죽고 무엇이 사는지 못 박는다.
 *
 * <p>{@code storage-root}·{@code signing-secret} 이 없으면
 * {@code UnavailableObjectStorageAdapter} 가 등록된다. 그때 <b>기동은 성공</b>해야 하고
 * <b>목록·상세는 200</b> 이어야 하며 <b>file-access 만 503</b> 이어야 한다.</p>
 *
 * <p>🔴 이 클래스가 기본 3키 조합을 그대로 쓰는 것이 요점이다 — 저장소 프로퍼티를 <b>안</b>
 * 준 상태가 곧 미설정이다. 조합이 기존과 같아 스프링 컨텍스트도 재사용된다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReportStorageUnconfiguredIntegrationTest extends MembershipRlsEnforcedSupport {

	private static final String REPORTS =
		"/api/v1/member/parents/me/children/{studentId}/reports";
	private static final String REPORT = REPORTS + "/{reportId}";
	private static final String FILE_ACCESS = REPORT + "/file-access";
	private static final String DOWNLOAD = "/api/v1/member/files/reports/{token}";

	private static final String MONTH = "2026-08";
	private static final String CHECKSUM = "sha256:" + "b".repeat(64);

	@Autowired MockMvc mockMvc;
	@Autowired MemberRateLimiter rateLimiter;

	private JdbcTemplate admin;
	private UUID parentAccountId;
	private UUID studentProfileId;
	private UUID reportId;

	@BeforeEach
	void setUp() {
		admin = adminJdbcTemplate();
		rateLimiter.overridePermitsForTesting(1000);
		clearFixtures(admin);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

		parentAccountId = insertAccount(admin, "parent@example.com", "PARENT", now);
		UUID parentProfileId = insertParent(admin, parentAccountId, "박학부모", now);
		UUID studentAccountId = insertAccount(admin, "student@example.com", "STUDENT", now);
		studentProfileId = MemberPostgresSupport.insertStudentProfile(
			admin, studentAccountId, "박학생", 2, now);
		UUID teacherId = insertTeacher(admin, "teacher@example.com", "김강사", now);

		admin.update("INSERT INTO parent_student_relationships"
			+ " (id, parent_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentProfileId, studentProfileId, now, now);
		admin.update("INSERT INTO teacher_student_relationships"
			+ " (id, teacher_id, student_id, status, started_at, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentProfileId, now, now);

		reportId = UUID.randomUUID();
		admin.update("INSERT INTO member_published_reports (id, student_id, teacher_id,"
			+ " report_month, month_zone, revision, status, snapshot_version, created_at,"
			+ " updated_at, published_at)"
			+ " VALUES (?, ?, ?, ?, 'Asia/Seoul', 1, 'PUBLISHED', 'rs-1', ?, ?, ?)",
			reportId, studentProfileId, teacherId, MONTH, now, now, now);
		admin.update("INSERT INTO member_report_files (id, report_id, student_id, teacher_id,"
			+ " published_at, object_key, checksum, content_type, size_bytes, page_count,"
			+ " created_at) VALUES (?, ?, ?, ?, ?, '2026-08/x.pdf', ?, 'application/pdf', 9,"
			+ " NULL, ?)",
			UUID.randomUUID(), reportId, studentProfileId, teacherId, now, CHECKSUM, now);
	}

	@Test
	@DisplayName("🔴 저장소 미설정이어도 기동은 성공하고 목록·상세는 200 이다")
	void listAndDetailStillWork() throws Exception {
		mockMvc.perform(get(REPORTS, studentProfileId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			// 파일 행은 있으므로 hasPdf 는 여전히 true — 저장소 가용성과 무관하다(계약 §4).
			.andExpect(jsonPath("$.data.items[0].hasPdf").value(true));
		mockMvc.perform(get(REPORT, studentProfileId, reportId).with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reportId").value(reportId.toString()));
	}

	@Test
	@DisplayName("🔴 저장소 미설정이면 file-access 만 503 이다")
	void onlyFileAccessDegrades() throws Exception {
		mockMvc.perform(post(FILE_ACCESS, studentProfileId, reportId).with(parent()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
	}

	@Test
	@DisplayName("🔴 서명 키가 없으면 어떤 토큰도 통과하지 못한다 — 「미설정=전부 통과」가 아니다")
	void downloadRejectsEverythingWithoutSigningSecret() throws Exception {
		mockMvc.perform(get(DOWNLOAD, "aaaa.bbbb")).andExpect(status().isNotFound());
	}

	private RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}
}
