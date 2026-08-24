package com.checkon.roster.infrastructure.persistence;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.roster.domain.TeacherProfile;

/**
 * 인증된 TEACHER Account를 Roster의 TeacherProfile ID로 연결한다.
 */
public interface TeacherProfileRepository
	extends JpaRepository<TeacherProfile, UUID> {

	Optional<TeacherProfile> findByAccount_Id(UUID accountId);
}

