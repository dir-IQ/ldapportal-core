// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.audit.AuditQueryCriteria;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import com.ldapportal.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditQueryServiceExportTest {

    private final AuditEventRepository repo = mock(AuditEventRepository.class);
    private final AuditQueryService service = new AuditQueryService(repo, new ObjectMapper());

    private static AuditEvent syncUpdate(String dn) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "directory-sync");
        detail.put("operation", "incremental");
        detail.put("attributes", List.of("mail", "member"));
        return AuditEvent.builder()
                .source(AuditSource.INTERNAL).action(AuditAction.ENTRY_UPDATE)
                .directoryName("dst").targetDn(dn).detail(detail)
                .occurredAt(OffsetDateTime.parse("2026-10-27T10:15:00Z"))
                .build();
    }

    @Test
    void writesEveryMatchingEvent_withTheSyncColumns() {
        when(repo.findAll(any(), any(), any(), any(), eq("directory-sync"), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(syncUpdate("uid=a,dc=dst")), PageRequest.of(0, 1000), 1));
        StringWriter out = new StringWriter();

        AuditQueryService.ExportResult result = service.exportCsv(
                AuditQueryCriteria.builder().source("directory-sync").build(), null, out);

        assertThat(result).isEqualTo(new AuditQueryService.ExportResult(1, 1, false));
        String[] lines = out.toString().split("\r\n");
        assertThat(lines[0]).isEqualTo("\"occurredAt\",\"action\",\"directory\",\"targetDn\",\"actor\","
                + "\"actorType\",\"source\",\"operation\",\"attributes\",\"correlationId\",\"origin\",\"detail\"");
        assertThat(lines[1]).startsWith("\"2026-10-27T10:15Z\",\"entry.update\",\"dst\",\"uid=a,dc=dst\",\"\",\"\","
                + "\"directory-sync\",\"incremental\",\"mail|member\",\"\",\"INTERNAL\",");
    }

    @Test
    void withNoEnd_pinsTheWindowToWhenTheExportStarted() {
        when(repo.findAll(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        OffsetDateTime before = OffsetDateTime.now();

        service.exportCsv(AuditQueryCriteria.EMPTY, null, new StringWriter());

        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).findAll(any(), any(), any(), any(), any(), any(), isNull(), to.capture(), any());
        assertThat(to.getValue()).isAfterOrEqualTo(before);
    }

    @Test
    void readsPageAfterPage_andStopsAtTheCap() {
        int total = AuditQueryService.MAX_EXPORT_ROWS + 10;
        when(repo.findAllByDirectoryIds(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(8);
                    List<AuditEvent> rows = new ArrayList<>();
                    for (int i = 0; i < p.getPageSize(); i++) {
                        rows.add(syncUpdate("uid=u" + (p.getOffset() + i) + ",dc=dst"));
                    }
                    return new PageImpl<>(rows, p, total);
                });
        StringWriter out = new StringWriter();

        AuditQueryService.ExportResult result = service.exportCsv(AuditQueryCriteria.EMPTY,
                Set.of(UUID.randomUUID()), out);

        assertThat(result.written()).isEqualTo(AuditQueryService.MAX_EXPORT_ROWS);
        assertThat(result.matched()).isEqualTo(total);
        assertThat(result.truncated()).isTrue();
        assertThat(out.toString().split("\r\n")).hasSize(AuditQueryService.MAX_EXPORT_ROWS + 1);
    }
}
