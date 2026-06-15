// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.DismissedSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public interface DismissedSuggestionRepository extends JpaRepository<DismissedSuggestion, UUID> {

    java.util.List<DismissedSuggestion> findAllByAccountId(UUID accountId);

    boolean existsByAccountIdAndSuggestionKey(UUID accountId, String suggestionKey);

    void deleteByAccountIdAndSuggestionKey(UUID accountId, String suggestionKey);
}
