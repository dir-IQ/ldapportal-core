// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.config.AppProperties;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BootstrapServiceTest {

    @Mock
    private AccountRepository accountRepo;

    private AppProperties   appProperties;
    private PasswordEncoder passwordEncoder;
    private BootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getBootstrap().getSuperadmin().setUsername("superadmin");
        appProperties.getBootstrap().getSuperadmin().setPassword("s3cr3t!");

        passwordEncoder  = new BCryptPasswordEncoder();
        bootstrapService = new BootstrapService(accountRepo, appProperties, passwordEncoder);
    }

    @Test
    void createsAccount_whenNoLocalSuperadminExists() throws Exception {
        when(accountRepo.countByRoleAndAuthTypeAndActiveTrue(AccountRole.SUPERADMIN, AccountType.LOCAL))
                .thenReturn(0L);

        bootstrapService.run(new DefaultApplicationArguments());

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepo).save(captor.capture());

        Account saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("superadmin");
        assertThat(saved.getAuthType()).isEqualTo(AccountType.LOCAL);
        assertThat(saved.getRole()).isEqualTo(AccountRole.SUPERADMIN);
        assertThat(saved.isActive()).isTrue();
        assertThat(passwordEncoder.matches("s3cr3t!", saved.getPasswordHash())).isTrue();
    }

    @Test
    void skipsBootstrap_whenLocalSuperadminAlreadyExists() throws Exception {
        when(accountRepo.countByRoleAndAuthTypeAndActiveTrue(AccountRole.SUPERADMIN, AccountType.LOCAL))
                .thenReturn(1L);

        bootstrapService.run(new DefaultApplicationArguments());

        verify(accountRepo, never()).save(any());
    }
}
