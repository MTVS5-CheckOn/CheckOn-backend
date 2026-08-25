package com.checkon.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.checkon.member.support.MemberPostgresSupport;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 가입·로그인 3개 엔드포인트. 분기표 §1 의 학생 가입 7 · 학부모 가입 4 · 로그인 8 에 대응한다.
 *
 * <p>🔴 가입은 「201 이 온다」로 끝내지 않는다. {@code accounts} 하나만 남고 나머지가 롤백되는
 * 상태가 가장 위험하므로 <b>네 테이블에 행이 실제로 있는지</b>까지 본다(분기표 §1 주석).</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MemberAuthIntegrationTest extends MemberPostgresSupport {

	private static final String STUDENT_SIGN_UP = "/api/v1/member/auth/students/sign-up";
	private static final String PARENT_SIGN_UP = "/api/v1/member/auth/parents/sign-up";
	private static final String STUDENT_LOGIN = "/api/v1/member/auth/students/login";

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		// 🔴 로그인 테스트가 authentication_sessions 를 남긴다. FK 가 RESTRICT 라
		//    이걸 먼저 지우지 않으면 다음 테스트의 정리가 통째로 실패한다.
		jdbcTemplate.update("DELETE FROM authentication_sessions");
		jdbcTemplate.update("DELETE FROM member_student_activation");
		jdbcTemplate.update("DELETE FROM member_display_names");
		jdbcTemplate.update("DELETE FROM member_student_public_ids");
		jdbcTemplate.update("DELETE FROM student_profiles");
		jdbcTemplate.update("DELETE FROM parent_profiles");
		jdbcTemplate.update("DELETE FROM account_password_credentials");
		jdbcTemplate.update("DELETE FROM accounts WHERE role <> 'TEACHER'");
	}

	@Test
	@DisplayName("학생 가입 — 201 과 함께 네 테이블에 행이 실제로 생긴다")
	void signUpStudentCreatesEveryRow() throws Exception {
		MvcResult result = mockMvc.perform(post(STUDENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"s1@example.com","password":"password123","name":"김학생",
					 "grade":2,"termsAgreed":true}"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.role").value("STUDENT"))
			.andExpect(jsonPath("$.data.activationStatus").value("PENDING_PARENT_LINK"))
			.andExpect(jsonPath("$.data.studentPublicId").exists())
			.andReturn();

		UUID accountId = UUID.fromString(readJson(result, "accountId"));
		// 🔴 「201 이 왔다」가 아니라 「행이 있다」를 본다. 하나라도 0 이면 부분 커밋이다.
		assertThat(count("SELECT count(*) FROM accounts WHERE id = ?", accountId)).isOne();
		assertThat(count("SELECT count(*) FROM student_profiles WHERE account_id = ?", accountId))
			.isOne();
		assertThat(count("SELECT count(*) FROM member_display_names WHERE account_id = ?",
			accountId)).isOne();
		Integer publicIds = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM member_student_public_ids p"
				+ " JOIN student_profiles s ON s.id = p.student_id WHERE s.account_id = ?",
			Integer.class, accountId);
		assertThat(publicIds).isOne();
		Integer activation = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM member_student_activation a"
				+ " JOIN student_profiles s ON s.id = a.student_id WHERE s.account_id = ?",
			Integer.class, accountId);
		assertThat(activation).isOne();
	}

	@Test
	@DisplayName("학생 가입 — 공개 ID 는 STU- 접두 형식이다")
	void signUpStudentIssuesFormattedPublicId() throws Exception {
		MvcResult result = mockMvc.perform(post(STUDENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"s2@example.com","password":"password123","name":"김학생",
					 "grade":1,"termsAgreed":true}"""))
			.andExpect(status().isCreated()).andReturn();

		assertThat(readJson(result, "studentPublicId")).matches("^STU-[A-Z0-9]{6,12}$");
	}

	@Test
	@DisplayName("🔴 학부모 가입 — parent_profiles 는 FORCE RLS 라 컨텍스트 순서가 틀리면 500 이다")
	void signUpParentInsertsUnderRlsContext() throws Exception {
		MvcResult result = mockMvc.perform(post(PARENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"p1@example.com","password":"password123","name":"박학부모",
					 "termsAgreed":true}"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.role").value("PARENT"))
			.andReturn();

		UUID accountId = UUID.fromString(readJson(result, "accountId"));
		assertThat(count("SELECT count(*) FROM parent_profiles WHERE account_id = ?", accountId))
			.as("0 이면 컨텍스트 순서가 틀린 것이다(분기표 §1 학부모 가입)")
			.isOne();
	}

	@Test
	@DisplayName("🔴 학부모 가입 응답은 studentPublicId·activationStatus 키를 두고 값만 null")
	void parentSignUpKeepsNullKeys() throws Exception {
		String body = mockMvc.perform(post(PARENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"p2@example.com","password":"password123","name":"박학부모",
					 "termsAgreed":true}"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.studentPublicId").doesNotExist())
			.andExpect(jsonPath("$.data.activationStatus").doesNotExist())
			.andReturn().getResponse().getContentAsString();

		// 🔴 jsonPath 의 doesNotExist 는 null 값에도 참이다. 키 자체가 있는지는 원문으로 본다.
		assertThat(body).contains("\"studentPublicId\":null");
		assertThat(body).contains("\"activationStatus\":null");
	}

	@Test
	@DisplayName("이메일 중복 — 409 EMAIL_ALREADY_EXISTS")
	void duplicateEmailIsConflict() throws Exception {
		String payload = """
			{"email":"dup@example.com","password":"password123","name":"김학생",
			 "grade":1,"termsAgreed":true}""";
		mockMvc.perform(post(STUDENT_SIGN_UP)
			.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isCreated());

		mockMvc.perform(post(STUDENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
	}

	@Test
	@DisplayName("약관 미동의 — 400 INVALID_REQUEST")
	void termsMustBeAgreed() throws Exception {
		mockMvc.perform(post(STUDENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"t1@example.com","password":"password123","name":"김학생",
					 "grade":1,"termsAgreed":false}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("학년 범위 밖 — 400 INVALID_REQUEST")
	void gradeOutOfRangeIsRejected() throws Exception {
		mockMvc.perform(post(STUDENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"g1@example.com","password":"password123","name":"김학생",
					 "grade":4,"termsAgreed":true}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("비밀번호 8자 미만 — 400 INVALID_REQUEST")
	void shortPasswordIsRejected() throws Exception {
		mockMvc.perform(post(STUDENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"pw@example.com","password":"short","name":"김학생",
					 "grade":1,"termsAgreed":true}"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("🔴 학생 로그인 성공 — 쿠키 Path 가 /api/v1/auth 여야 기존 refresh 가 읽는다")
	void loginStudentIssuesRefreshCookieOnSharedPath() throws Exception {
		String publicId = signUpStudent("login1@example.com");

		MvcResult result = mockMvc.perform(post(STUDENT_LOGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentPublicId\":\"" + publicId
					+ "\",\"password\":\"password123\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").exists())
			.andExpect(jsonPath("$.data.account.role").value("STUDENT"))
			.andExpect(jsonPath("$.data.account.teacherProfileId").doesNotExist())
			.andReturn();

		List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		assertThat(cookies).anySatisfy(cookie -> {
			assertThat(cookie).contains("CHECKON_REFRESH");
			assertThat(cookie).contains("Path=/api/v1/auth");
			assertThat(cookie).contains("HttpOnly");
		});
		// 🔴 refresh 원문이 본문에 실리면 HttpOnly 가 무의미해진다.
		assertThat(result.getResponse().getContentAsString()).doesNotContain("refreshToken");
	}

	@Test
	@DisplayName("🔴 공개 ID 를 소문자·공백·하이픈 없이 보내도 같은 계정으로 로그인된다")
	void loginNormalizesPublicId() throws Exception {
		String publicId = signUpStudent("login2@example.com");
		String mangled = publicId.replace("STU-", "stu ").toLowerCase(java.util.Locale.ROOT);

		mockMvc.perform(post(STUDENT_LOGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentPublicId\":\"" + mangled
					+ "\",\"password\":\"password123\"}"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("🔴 없는 공개 ID 와 틀린 비밀번호가 같은 401 INVALID_CREDENTIALS 다")
	void unknownIdAndWrongPasswordAreIndistinguishable() throws Exception {
		String publicId = signUpStudent("login3@example.com");

		mockMvc.perform(post(STUDENT_LOGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentPublicId\":\"STU-ZZZZZZ\",\"password\":\"password123\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

		mockMvc.perform(post(STUDENT_LOGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentPublicId\":\"" + publicId
					+ "\",\"password\":\"wrongpassword\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	@DisplayName("🔴 형식이 깨진 공개 ID 도 400 이 아니라 401 이다 — 형식 응답이 열거 단서가 된다")
	void malformedPublicIdIsAlsoUnauthorized() throws Exception {
		mockMvc.perform(post(STUDENT_LOGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentPublicId\":\"!!!\",\"password\":\"password123\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	@DisplayName("🔴 결함 4 재현 — SUSPENDED 계정은 401 이되 ACCOUNT_NOT_ACTIVE 다")
	void suspendedAccountGetsItsOwnCode() throws Exception {
		String publicId = signUpStudent("suspended@example.com");
		jdbcTemplate.update("UPDATE accounts SET status = 'SUSPENDED' WHERE email = ?",
			"suspended@example.com");

		mockMvc.perform(post(STUDENT_LOGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentPublicId\":\"" + publicId
					+ "\",\"password\":\"password123\"}"))
			.andExpect(status().isUnauthorized())
			// 🔴 INVALID_CREDENTIALS 로 뭉개면 정지 계정 안내를 못 한다(계약 :153).
			.andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_ACTIVE"));
	}

	@Test
	@DisplayName("🔴 대기 학생도 로그인 자체는 200 이다 — 막히는 건 그 뒤 기능이다")
	void pendingStudentCanStillLogIn() throws Exception {
		String publicId = signUpStudent("pending@example.com");

		mockMvc.perform(post(STUDENT_LOGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentPublicId\":\"" + publicId
					+ "\",\"password\":\"password123\"}"))
			.andExpect(status().isOk());
	}

	private String signUpStudent(String email) throws Exception {
		MvcResult result = mockMvc.perform(post(STUDENT_SIGN_UP)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"password123\","
					+ "\"name\":\"김학생\",\"grade\":1,\"termsAgreed\":true}"))
			.andExpect(status().isCreated()).andReturn();
		return readJson(result, "studentPublicId");
	}

	private String readJson(MvcResult result, String field) throws Exception {
		// 🔴 정규식으로 JSON 을 파싱하지 않는다. 앞서 그렇게 했다가 값이 응답에 있는데도
		//    "없다"고 실패했다 — 테스트가 제품이 아니라 자기 파서를 검증하게 된다.
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data." + field);
	}

	private Integer count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}
}
