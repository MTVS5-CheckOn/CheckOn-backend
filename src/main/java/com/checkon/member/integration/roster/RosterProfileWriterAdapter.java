package com.checkon.member.integration.roster;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 학생·학부모 프로필 행을 네이티브 SQL 로 만든다.
 *
 * <p>🔴 왜 엔티티를 안 쓰나 —
 * {@code StudentProfile}(roster)에는 {@code account} 를 연결하는 public 경로가 없다
 * ({@code create(alias, grade, createdAt)} 뿐, 실측). {@code ParentProfile} 엔티티는 아예 없다.
 * 둘 다 팀원 파일이라 고칠 수 없다.</p>
 *
 * <p>🔴 member 안에 {@code @Entity @Table(name="student_profiles")} 를 새로 만들지 않는다.
 * 팀원이 나중에 {@code ParentProfile} 엔티티를 추가하면 같은 테이블에 두 매핑이 생겨
 * flush 순서로 깨진다. {@code TenantDatabaseRoleSafetyVerifier} 가 같은 방식의 선례다.</p>
 */
@Component
public class RosterProfileWriterAdapter {

	// 🔴 account_id 와 account_linked_at 을 둘 다 채운다.
	//    ck_student_profiles_account_link 가 한쪽만 있는 상태를 거절한다.
	private static final String INSERT_STUDENT = """
		INSERT INTO student_profiles
		    (id, account_id, alias, grade, account_linked_at, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)
		""";

	// 🔴 account_role 은 컬럼 기본값 'PARENT' 에 맡긴다. 복합 FK (account_id, account_role) 가
	//    accounts(id, role) 를 참조하므로 accounts 행이 flush 된 뒤여야 한다.
	private static final String INSERT_PARENT = """
		INSERT INTO parent_profiles (id, account_id, created_at, updated_at)
		VALUES (?, ?, ?, ?)
		""";

	private static final String FIND_STUDENT_ACCOUNT = """
		SELECT account_id FROM student_profiles WHERE id = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public RosterProfileWriterAdapter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public UUID insertStudentProfile(UUID accountId, String alias, Integer grade, Instant now) {
		UUID id = UUID.randomUUID();
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.update(INSERT_STUDENT, id, accountId, alias, grade, at, at, at);
		return id;
	}

	public UUID insertParentProfile(UUID accountId, Instant now) {
		UUID id = UUID.randomUUID();
		OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		jdbcTemplate.update(INSERT_PARENT, id, accountId, at, at);
		return id;
	}

	public Optional<UUID> findStudentAccountId(UUID studentProfileId) {
		return jdbcTemplate.query(FIND_STUDENT_ACCOUNT, rs -> rs.next()
			? Optional.ofNullable(rs.getObject(1, UUID.class))
			: Optional.empty(), studentProfileId);
	}
}
