package com.checkon.member.integration.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 학생 홈 인사말의 원본인 {@code student_profiles.alias}만 읽는 경계 어댑터. */
@Component
public class StudentAliasReader {

	private static final String FIND = "SELECT alias FROM student_profiles WHERE id = ?";

	private final JdbcTemplate jdbcTemplate;

	public StudentAliasReader(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<String> find(UUID studentId) {
		return jdbcTemplate.query(FIND, resultSet -> resultSet.next()
			? Optional.ofNullable(resultSet.getString(1))
			: Optional.empty(), studentId);
	}
}
