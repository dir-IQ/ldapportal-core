// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.DirectoryUserBaseDn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DirectoryUserBaseDnRepository extends JpaRepository<DirectoryUserBaseDn, UUID> {

    List<DirectoryUserBaseDn> findAllByDirectoryIdOrderByDisplayOrderAsc(UUID directoryId);

    void deleteAllByDirectoryId(UUID directoryId);
}
