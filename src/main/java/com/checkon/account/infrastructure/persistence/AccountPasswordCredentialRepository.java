package com.checkon.account.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkon.account.domain.AccountPasswordCredential;

public interface AccountPasswordCredentialRepository
	extends JpaRepository<AccountPasswordCredential, UUID> {
}

