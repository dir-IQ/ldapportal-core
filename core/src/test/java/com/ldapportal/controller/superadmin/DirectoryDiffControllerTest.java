// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.RequiresSuperadminPermission;
import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.dto.diff.DirectoryDiffEntryResponse;
import com.ldapportal.dto.diff.DirectoryDiffRunResponse;
import com.ldapportal.entity.enums.DirectoryDiffCategory;
import com.ldapportal.entity.enums.DirectoryDiffPairing;
import com.ldapportal.entity.enums.DirectoryDiffPhase;
import com.ldapportal.entity.enums.DirectoryDiffStatus;
import com.ldapportal.entity.enums.SuperadminPermission;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.service.DirectoryDiffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DirectoryDiffController.class)
class DirectoryDiffControllerTest extends BaseControllerTest {

    private static final String BODY = """
            {"a":{"directoryId":"%s"},"b":{"directoryId":"%s"},"pairBy":"DN"}
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DirectoryDiffService service;

    private static DirectoryDiffRunResponse running(UUID id) {
        return new DirectoryDiffRunResponse(id, DirectoryDiffStatus.RUNNING, DirectoryDiffPhase.READING_A, 0, 0,
                null, null, "superadmin", OffsetDateTime.now(), OffsetDateTime.now(), null,
                null, null, DirectoryDiffPairing.DN, null, List.of(), List.of(),
                new DirectoryDiffRunResponse.Counts(0, 0, 0, 0), List.of());
    }

    @Test
    void startReturns202ForSuperadmin() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.start(any(), any())).thenReturn(running(id));
        mockMvc.perform(post("/api/v1/superadmin/directory-diffs").with(authentication(superadminAuth()))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void adminIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/directory-diffs").with(authentication(adminAuth()))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/superadmin/directory-diffs").with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void validationAndConcurrencyErrorsMap() throws Exception {
        when(service.start(any(), any())).thenThrow(new IllegalArgumentException("bad filter"));
        mockMvc.perform(post("/api/v1/superadmin/directory-diffs").with(authentication(superadminAuth()))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());

        doThrow(new ConflictException("busy")).when(service).start(any(), any());
        mockMvc.perform(post("/api/v1/superadmin/directory-diffs").with(authentication(superadminAuth()))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @SuppressWarnings("unchecked")
    void csvExportsAGroup() throws Exception {
        UUID id = UUID.randomUUID();
        doAnswer(inv -> {
            Consumer<DirectoryDiffEntryResponse> sink = inv.getArgument(2);
            sink.accept(new DirectoryDiffEntryResponse(1, DirectoryDiffCategory.DIFFERENT,
                    "cn=a\\,b,dc=a", "cn=a\\,b,dc=b", List.of("cn", "mail"), null));
            return null;
        }).when(service).forEachEntry(eq(id), eq(DirectoryDiffCategory.DIFFERENT), any(Consumer.class));

        mockMvc.perform(get("/api/v1/superadmin/directory-diffs/{id}/entries.csv", id)
                        .param("category", "DIFFERENT").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"directory-diff-" + id + "-different.csv\""))
                .andExpect(content().string("group,dn_a,dn_b,attributes,note\r\n"
                        + "DIFFERENT,\"cn=a\\,b,dc=a\",\"cn=a\\,b,dc=b\",cn mail,\r\n"));
    }

    @Test
    void csvEscapesQuotesAndFormulas() {
        assertThat(DirectoryDiffController.csv(null)).isEmpty();
        assertThat(DirectoryDiffController.csv("plain")).isEqualTo("plain");
        assertThat(DirectoryDiffController.csv("say \"hi\", ok")).isEqualTo("\"say \"\"hi\"\", ok\"");
        assertThat(DirectoryDiffController.csv("=cmd()")).isEqualTo("'=cmd()");
    }

    @Test
    void classRequiresTheDirectoryDiffPermission() {
        RequiresSuperadminPermission ann =
                DirectoryDiffController.class.getAnnotation(RequiresSuperadminPermission.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo(SuperadminPermission.MANAGE_DIRECTORY_DIFF);
    }
}
