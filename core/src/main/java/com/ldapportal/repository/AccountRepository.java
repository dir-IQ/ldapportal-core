// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUsername(String username);

    Optional<Account> findByUsernameAndActiveTrue(String username);

    List<Account> findAllByRole(AccountRole role);

    List<Account> findAllByRoleAndActiveTrue(AccountRole role);

    long countByRoleAndActiveTrue(AccountRole role);

    long countByRoleAndActiveTrueAndIdNot(AccountRole role, UUID id);

    boolean existsByUsername(String username);

    List<Account> findAllByAuthType(AccountType authType);

    long countByRoleAndAuthTypeAndActiveTrue(AccountRole role, AccountType authType);

    long countByRoleAndAuthTypeAndActiveTrueAndIdNot(AccountRole role, AccountType authType, UUID id);
}
