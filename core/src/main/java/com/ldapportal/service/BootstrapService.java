// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.config.AppProperties;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the initial LOCAL superadmin account on the very first startup.
 *
 * <p>The bootstrap runs only when there are no active LOCAL superadmin
 * accounts in the database, preventing accidental re-creation on subsequent
 * restarts.  Credentials are sourced from {@code BOOTSTRAP_SUPERADMIN_USERNAME}
 * and {@code BOOTSTRAP_SUPERADMIN_PASSWORD} environment variables (see
 * {@link AppProperties.Bootstrap}).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BootstrapService implements ApplicationRunner {

    private final AccountRepository accountRepo;
    private final AppProperties     appProperties;
    private final PasswordEncoder   passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existingCount = accountRepo
                .countByRoleAndAuthTypeAndActiveTrue(AccountRole.SUPERADMIN, AccountType.LOCAL);

        if (existingCount > 0) {
            log.debug("Bootstrap skipped — {} active LOCAL superadmin(s) already exist",
                    existingCount);
            return;
        }

        AppProperties.Bootstrap.Superadmin cfg =
                appProperties.getBootstrap().getSuperadmin();

        Account account = new Account();
        account.setUsername(cfg.getUsername());
        account.setRole(AccountRole.SUPERADMIN);
        account.setAuthType(AccountType.LOCAL);
        account.setPasswordHash(passwordEncoder.encode(cfg.getPassword()));
        account.setActive(true);

        accountRepo.save(account);
        log.info("Bootstrap: created LOCAL superadmin [{}]", cfg.getUsername());
    }
}
