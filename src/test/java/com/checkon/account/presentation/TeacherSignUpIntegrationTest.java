package com.checkon.account.presentation;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.application.TeacherSignUpService;
import com.checkon.account.infrastructure.persistence.AccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 PostgreSQL에서 강사 가입 전체 흐름을 검증한다.
 *
 * <p>HTTP 응답만 확인하지 않고 Flyway 제약, UUIDv7, 비밀번호 해시,
 * 트랜잭션 롤백과 대소문자 무시 중복까지 함께 확인한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class TeacherSignUpIntegrationTest {

	private static final String RAW_PASSWORD = "safe-password-123";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TeacherSignUpService teacherSignUpService;

	@Autowired
	private AccountRepository accountRepository;

	@BeforeEach
	void clearAccountData() {
		jdbcTemplate.update("DELETE FROM teacher_profiles");
		jdbcTemplate.update("DELETE FROM account_password_credentials");
		jdbcTemplate.update("DELETE FROM accounts");
	}

	@Test
	void signsUpTeacherAndStoresOnlyNormalizedEmailAndPasswordHash()
		throws Exception {
		byte[] response = mockMvc.perform(post("/api/v1/auth/sign-up/teachers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": " Teacher@Example.COM ",
					  "password": "safe-password-123",
					  "displayName": " 김서현 "
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(header().string(
				"Location",
				org.hamcrest.Matchers.startsWith("/api/v1/accounts/")
			))
			.andExpect(jsonPath("$.data.role").value("TEACHER"))
			.andExpect(jsonPath("$.data.email").value("teacher@example.com"))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		String accountIdText = new tools.jackson.databind.ObjectMapper()
			.readTree(response)
			.at("/data/accountId")
			.asText();
		UUID accountId = UUID.fromString(accountIdText);
		assertThat(accountId.version()).isEqualTo(7);

		String storedEmail = jdbcTemplate.queryForObject(
			"SELECT email FROM accounts WHERE id = ?",
			String.class,
			accountId
		);
		String passwordHash = jdbcTemplate.queryForObject(
			"""
				SELECT password_hash
				FROM account_password_credentials
				WHERE account_id = ?
				""",
			String.class,
			accountId
		);
		String displayName = jdbcTemplate.queryForObject(
			"SELECT display_name FROM teacher_profiles WHERE account_id = ?",
			String.class,
			accountId
		);

		assertThat(storedEmail).isEqualTo("teacher@example.com");
		assertThat(passwordHash)
			.isNotEqualTo(RAW_PASSWORD)
			.startsWith("$2");
		assertThat(passwordEncoder.matches(RAW_PASSWORD, passwordHash)).isTrue();
		assertThat(displayName).isEqualTo("김서현");
	}

	@Test
	void rejectsDuplicateEmailInApplicationRegardlessOfCase() throws Exception {
		teacherSignUpService.signUp(
			"teacher@example.com",
			RAW_PASSWORD,
			"첫 강사"
		);

		mockMvc.perform(post("/api/v1/auth/sign-up/teachers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "TEACHER@example.com",
					  "password": "safe-password-123",
					  "displayName": "두 번째 강사"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));

		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@Test
	void databaseRejectsCaseInsensitiveDuplicateEmail() {
		insertAccount("teacher@example.com");

		assertThatThrownBy(() -> insertAccount("TEACHER@example.com"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rollsBackAccountWhenTeacherProfileCreationFails() {
		assertThatThrownBy(() -> teacherSignUpService.signUp(
			"teacher@example.com",
			RAW_PASSWORD,
			"   "
		)).isInstanceOf(IllegalArgumentException.class);

		assertThat(accountRepository.count()).isZero();
		Integer credentialCount = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM account_password_credentials",
			Integer.class
		);
		assertThat(credentialCount).isZero();
	}

	@Test
	void rejectsInvalidEmailAndShortPassword() throws Exception {
		mockMvc.perform(post("/api/v1/auth/sign-up/teachers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "not-an-email",
					  "password": "short",
					  "displayName": "김서현"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.fieldErrors").isArray());

		assertThat(accountRepository.count()).isZero();
	}

	private void insertAccount(String email) {
		jdbcTemplate.update(
			"""
				INSERT INTO accounts (
				    id,
				    email,
				    role,
				    status,
				    created_at
				)
				VALUES (uuidv7(), ?, 'TEACHER', 'ACTIVE', now())
				""",
			email
		);
	}
}
