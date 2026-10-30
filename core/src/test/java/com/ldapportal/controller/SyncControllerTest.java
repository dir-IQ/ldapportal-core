// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.superadmin.SyncLinkController;
import com.ldapportal.controller.superadmin.SyncSetController;
import com.ldapportal.dto.sync.MembershipResponse;
import com.ldapportal.dto.sync.RecomputeKeyRequest;
import com.ldapportal.dto.sync.SyncDeleteApproval;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncLinkResponse;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncRecheckResult;
import com.ldapportal.dto.sync.SyncReconcileRunDetail;
import com.ldapportal.dto.sync.SyncReconcileRunResponse;
import com.ldapportal.dto.sync.SyncRunFailureReason;
import com.ldapportal.dto.sync.SyncRunSettings;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncSetResponse;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncReconcileRunStatus;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.service.SyncConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SyncLinkController.class, SyncSetController.class})
class SyncControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean SyncConfigService service;
    @MockitoBean com.ldapportal.ldap.sync.SyncSetDefaultsProvider defaultsProvider;

    private static final UUID LINK = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SET = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String LINKS = "/api/v1/superadmin/sync/links";
    private static final String SETS = "/api/v1/superadmin/sync/sets";

    private SyncLinkRequest linkReq() {
        return new SyncLinkRequest("src->dst", UUID.randomUUID(), UUID.randomUUID(), true,
                SyncCaptureMode.APP_INTERCEPT, null, null);
    }

    private SyncLinkResponse linkResp() {
        return new SyncLinkResponse(LINK, "src->dst", UUID.randomUUID(), UUID.randomUUID(), true,
                SyncCaptureMode.APP_INTERCEPT, null, null, null, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), 0L);
    }

    private SyncSetRequest setReq() {
        return new SyncSetRequest(LINK, "people", "ou=people,dc=src", null, null, "ou=Users,dc=dst",
                "(objectClass=inetOrgPerson)", null, null, null, null, null, null, true, null);
    }

    private SyncSetResponse setResp() {
        return new SyncSetResponse(SET, LINK, "people", "ou=people,dc=src", null, null, "ou=Users,dc=dst",
                null, null, null, null, null, null, null, null, true,
                com.ldapportal.entity.enums.SyncLoggingLevel.SUMMARY, OffsetDateTime.now(), OffsetDateTime.now(), 0L,
                java.util.Map.of());
    }

    // ── authz ──────────────────────────────────────────────────────────────────

    @Test
    void listLinks_superadmin_returns200() throws Exception {
        given(service.listLinks()).willReturn(List.of(linkResp()));
        mockMvc.perform(get(LINKS).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("src->dst"));
    }

    @Test
    void createLink_adminRole_returns403() throws Exception {
        mockMvc.perform(post(LINKS).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkReq())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createLink_anonymous_returns401() throws Exception {
        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkReq())))
                .andExpect(status().isUnauthorized());
    }

    // ── CRUD + validation mapping ──────────────────────────────────────────────

    @Test
    void createLink_superadmin_returns201() throws Exception {
        given(service.createLink(any())).willReturn(linkResp());
        mockMvc.perform(post(LINKS).with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkReq())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(LINK.toString()));
    }

    @Test
    void createLink_serviceRejects_returns400() throws Exception {
        willThrow(new IllegalArgumentException("Source and target directories must differ"))
                .given(service).createLink(any());
        mockMvc.perform(post(LINKS).with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkReq())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createLink_blankDisplayName_returns400() throws Exception {
        SyncLinkRequest bad = new SyncLinkRequest("", UUID.randomUUID(), UUID.randomUUID(), true, null, null, null);
        mockMvc.perform(post(LINKS).with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSet_superadmin_returns201() throws Exception {
        given(service.createSet(any())).willReturn(setResp());
        mockMvc.perform(post(SETS).with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setReq())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("people"));
    }

    @Test
    void deleteLink_superadmin_deletesAsThePrincipal() throws Exception {
        mockMvc.perform(delete(LINKS + "/" + LINK).with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(service).deleteLink(eq(LINK),
                org.mockito.ArgumentMatchers.argThat(p -> p != null && "superadmin".equals(p.username())));
    }

    @Test
    void deleteLink_adminRole_returns403() throws Exception {
        mockMvc.perform(delete(LINKS + "/" + LINK).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteSet_superadmin_deletesAsThePrincipal() throws Exception {
        mockMvc.perform(delete(SETS + "/" + SET).with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(service).deleteSet(eq(SET),
                org.mockito.ArgumentMatchers.argThat(p -> p != null && "superadmin".equals(p.username())));
    }

    @Test
    void deleteImpact_superadmin_returnsTheCounts() throws Exception {
        given(service.deleteImpact(SET)).willReturn(
                new com.ldapportal.dto.sync.SyncSetDeleteImpact("people", 876, 3, 12, List.of("groups")));
        mockMvc.perform(get(SETS + "/" + SET + "/delete-impact").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managed").value(876))
                .andExpect(jsonPath("$.pendingReview").value(3))
                .andExpect(jsonPath("$.reconcileRuns").value(12))
                .andExpect(jsonPath("$.otherSets[0]").value("groups"));
    }

    @Test
    void setDefaults_listsWhatTheEngineFallsBackTo() throws Exception {
        given(defaultsProvider.defaults()).willReturn(new com.ldapportal.dto.sync.SyncSetDefaults(
                java.util.Map.of("ACTIVE_DIRECTORY", "objectGUID"), List.of("member", "secDN"), 3600,
                List.of("userPassword")));

        mockMvc.perform(get(SETS + "/defaults").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityKeyByDirectoryType.ACTIVE_DIRECTORY").value("objectGUID"))
                .andExpect(jsonPath("$.referenceAttributes[1]").value("secDN"))
                .andExpect(jsonPath("$.reconcileIntervalSeconds").value(3600))
                .andExpect(jsonPath("$.excludedAttributes[0]").value("userPassword"));
    }

    @Test
    void setDefaults_adminRole_returns403() throws Exception {
        mockMvc.perform(get(SETS + "/defaults").with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteSet_adminRole_returns403() throws Exception {
        mockMvc.perform(delete(SETS + "/" + SET).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    // ── inventory + triggers ────────────────────────────────────────────────────

    @Test
    void reconcile_superadmin_returnsCount() throws Exception {
        given(service.reconcileNow(SET, "superadmin")).willReturn(7);
        mockMvc.perform(post(SETS + "/" + SET + "/reconcile").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enumerated").value(7));
    }

    @Test
    void approveDelete_superadmin_returnsOutcome() throws Exception {
        UUID targetDir = UUID.randomUUID();
        given(service.approveDelete(eq(SET), eq("id-1"), any())).willReturn(
                new SyncDeleteApproval(SyncPreviewAction.DELETE, targetDir, "uid=a,dc=dst", null));
        mockMvc.perform(post(SETS + "/" + SET + "/memberships/id-1/approve-delete")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETE"))
                .andExpect(jsonPath("$.targetDn").value("uid=a,dc=dst"));
    }

    @Test
    void approveDelete_entryBackInScope_returns400() throws Exception {
        given(service.approveDelete(eq(SET), eq("id-1"), any()))
                .willThrow(new IllegalArgumentException("The source entry is back in scope"));
        mockMvc.perform(post(SETS + "/" + SET + "/memberships/id-1/approve-delete")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approveDelete_adminRole_returns403() throws Exception {
        mockMvc.perform(post(SETS + "/" + SET + "/memberships/id-1/approve-delete")
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconcileRuns_superadmin_returnsHistory_withClampedLimit() throws Exception {
        UUID runId = UUID.randomUUID();
        given(service.reconcileRuns(SET, 20, null)).willReturn(List.of(new SyncReconcileRunResponse(
                runId, SyncReconcileTrigger.MANUAL, "superadmin", SyncReconcileRunStatus.COMPLETE,
                OffsetDateTime.now(), OffsetDateTime.now(), true, 4741, 876, 4741L, 4741,
                3, 1, 0, 0, 0, 3865, 0, 872, 0, 0, 0, false, null, null,
                "app-2", OffsetDateTime.now(), null, null, null, null, false, 0, null, null, OffsetDateTime.now())));
        mockMvc.perform(get(SETS + "/" + SET + "/reconcile-runs").param("limit", "500")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(runId.toString()))
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"))
                .andExpect(jsonPath("$[0].noChange").value(3865))
                .andExpect(jsonPath("$[0].managedAfter").value(4741));
    }

    @Test
    void reconcileRuns_kindFilter_isPassedThrough() throws Exception {
        given(service.reconcileRuns(SET, 10, com.ldapportal.ldap.sync.SyncReconcileHistory.RunKind.INCREMENTAL))
                .willReturn(List.of());
        mockMvc.perform(get(SETS + "/" + SET + "/reconcile-runs").param("kind", "INCREMENTAL")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).reconcileRuns(SET, 10,
                com.ldapportal.ldap.sync.SyncReconcileHistory.RunKind.INCREMENTAL);
    }

    @Test
    void reconcileRunEntries_returnsTheRunsCsv() throws Exception {
        UUID runId = UUID.randomUUID();
        org.mockito.BDDMockito.willAnswer(inv -> {
            java.io.Writer w = inv.getArgument(2);
            w.write("\"outcome\",\"dn\",\"reason\"\r\n\"MODIFY\",\"uid=a,dc=dst\",\"\"\r\n");
            w.flush();
            return null;
        }).given(service).writeRunEntriesCsv(eq(SET), eq(runId), any());

        mockMvc.perform(get(SETS + "/" + SET + "/reconcile-runs/" + runId + "/entries.csv")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", "attachment; filename=\"sync-run-" + runId + ".csv\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"MODIFY\",\"uid=a,dc=dst\"")));
        org.mockito.Mockito.verify(service).requireRunEntries(SET, runId);
    }

    @Test
    void reconcileRunEntries_ofARunThatKeptOnlySamples_is400() throws Exception {
        UUID runId = UUID.randomUUID();
        willThrow(new IllegalArgumentException("This run kept only sample DNs"))
                .given(service).requireRunEntries(SET, runId);

        mockMvc.perform(get(SETS + "/" + SET + "/reconcile-runs/" + runId + "/entries.csv")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).writeRunEntriesCsv(any(), any(), any());
    }

    @Test
    void activity_returnsTheRunningReconcile_andContentCheck_withProgress() throws Exception {
        UUID runId = UUID.randomUUID();
        SyncReconcileRunResponse run = new SyncReconcileRunResponse(
                runId, SyncReconcileTrigger.MANUAL, "superadmin", SyncReconcileRunStatus.RUNNING,
                OffsetDateTime.now(), null, false, 120, 80, null, 37,
                0, 0, 0, 0, 0, 0, 0, 30, 0, 0, 0, false, null, null,
                "app-1", OffsetDateTime.now(), null, null, null, null, false, 7,
                com.ldapportal.entity.enums.SyncReconcilePhase.APPLYING, "cn=staff,ou=groups,dc=src",
                OffsetDateTime.now());
        var verify = new com.ldapportal.dto.sync.SyncVerifyProgressStatus(UUID.randomUUID(),
                com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING,
                com.ldapportal.entity.enums.SyncVerifyPhase.TARGET, 500, 0, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
        given(service.activity(SET)).willReturn(new com.ldapportal.dto.sync.SyncSetActivity(run, verify,
                OffsetDateTime.now()));
        mockMvc.perform(get(SETS + "/" + SET + "/activity").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconcile.phase").value("APPLYING"))
                .andExpect(jsonPath("$.reconcile.currentDn").value("cn=staff,ou=groups,dc=src"))
                .andExpect(jsonPath("$.reconcile.notInSet").value(7))
                .andExpect(jsonPath("$.verify.phase").value("TARGET"))
                .andExpect(jsonPath("$.asOf").exists());
    }

    @Test
    void notInSet_returnsThePage_withTheSearch() throws Exception {
        given(service.notInSet(SET, "bob", 0, 50)).willReturn(new com.ldapportal.dto.sync.SyncNotInSetPage(
                UUID.randomUUID(), OffsetDateTime.now(), false, 3, List.of(), List.of(
                        new com.ldapportal.dto.sync.SyncNotInSetPage.Entry("uid=bob,ou=people,dc=src", "FILTER",
                                "Doesn't match the applicability filter")), 1, 0, 50, 25));
        mockMvc.perform(get(SETS + "/" + SET + "/not-in-set").param("q", "bob")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.entries[0].sourceDn").value("uid=bob,ou=people,dc=src"))
                .andExpect(jsonPath("$.entries[0].label").value("Doesn't match the applicability filter"));
    }

    @Test
    void reconcileRun_superadmin_returnsDetail() throws Exception {
        UUID runId = UUID.randomUUID();
        SyncReconcileRunResponse run = new SyncReconcileRunResponse(
                runId, SyncReconcileTrigger.SCHEDULED, null, SyncReconcileRunStatus.COMPLETE,
                OffsetDateTime.now(), OffsetDateTime.now(), true, 10, 8, 9L, 10,
                1, 0, 0, 0, 0, 0, 0, 7, 2, 0, 0, false, null, null,
                "app-1", OffsetDateTime.now(), OffsetDateTime.now(), 0, 1, 0, true, 3, null, null, OffsetDateTime.now());
        given(service.reconcileRun(SET, runId)).willReturn(new SyncReconcileRunDetail(run,
                new SyncRunSettings("REVIEW", null, null, null, "ou=Users,dc=dst", null, null, null, 50, 20, 20),
                List.of(new SyncRunFailureReason("apply failed: NO_SUCH_OBJECT", 2, "apply failed: NO_SUCH_OBJECT — x")),
                java.util.Map.of("ADD", List.of("uid=a,dc=dst")), 25,
                List.of(new com.ldapportal.dto.sync.SyncNotInSetGroup("FILTER",
                        "Doesn't match the applicability filter", 3, List.of("ou=people,dc=src")))));
        mockMvc.perform(get(SETS + "/" + SET + "/reconcile-runs/" + runId).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.instanceName").value("app-1"))
                .andExpect(jsonPath("$.settings.deletePolicy").value("REVIEW"))
                .andExpect(jsonPath("$.failureReasons[0].count").value(2))
                .andExpect(jsonPath("$.samples.ADD[0]").value("uid=a,dc=dst"));
    }

    @Test
    void reconcileRun_ofAnotherSet_returns404() throws Exception {
        UUID runId = UUID.randomUUID();
        given(service.reconcileRun(SET, runId))
                .willThrow(new com.ldapportal.exception.ResourceNotFoundException("Reconcile run not found"));
        mockMvc.perform(get(SETS + "/" + SET + "/reconcile-runs/" + runId).with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void reconcileRuns_adminRole_returns403() throws Exception {
        mockMvc.perform(get(SETS + "/" + SET + "/reconcile-runs").with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void recheckTarget_superadmin_returnsResult() throws Exception {
        given(service.recheckTarget(SET, "superadmin")).willReturn(new SyncRecheckResult(4741, true));
        mockMvc.perform(post(SETS + "/" + SET + "/recheck-target").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").value(4741))
                .andExpect(jsonPath("$.reconcileQueued").value(true));
    }

    @Test
    void recheckTarget_adminRole_returns403() throws Exception {
        mockMvc.perform(post(SETS + "/" + SET + "/recheck-target").with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void recompute_superadmin_returns202() throws Exception {
        mockMvc.perform(post(SETS + "/" + SET + "/recompute").with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecomputeKeyRequest("uid=a,dc=x"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void memberships_superadmin_returns200_withTopLevelPageEnvelope() throws Exception {
        MembershipResponse row = new MembershipResponse(SET, "id-1", "uid=a,dc=src", "uid=a,dc=dst",
                MembershipState.APPLIED, null, null, null, null);
        given(service.listMemberships(any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 50), 120));
        mockMvc.perform(get(SETS + "/" + SET + "/memberships").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                // Stable top-level envelope (not Spring's nested page metadata).
                .andExpect(jsonPath("$.content[0].identity").value("id-1"))
                .andExpect(jsonPath("$.totalElements").value(120))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void dismissMembership_superadmin_returns204() throws Exception {
        mockMvc.perform(delete(SETS + "/" + SET + "/memberships/abc-123").with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
    }

    // ── per-entry preview ──────────────────────────────────────────────────────

    private static com.ldapportal.dto.sync.SyncPreviewPage previewPage() {
        var change = new com.ldapportal.dto.sync.SyncPreviewAttributeChange(
                "mail", List.of("old@x"), List.of("new@x"), 0, 0);
        var entry = new com.ldapportal.dto.sync.SyncPreviewEntry("id-1", "uid=a,ou=people,dc=src",
                "uid=a,ou=Users,dc=dst", null, com.ldapportal.dto.sync.SyncPreviewAction.MODIFY, null,
                List.of(change));
        return new com.ldapportal.dto.sync.SyncPreviewPage(List.of(entry), 0, 50, 1, 1, true, RUN);
    }

    private static final UUID RUN = UUID.fromString("30000000-0000-0000-0000-000000000003");

    private static com.ldapportal.dto.sync.SyncPreviewStatus runningStatus() {
        return new com.ldapportal.dto.sync.SyncPreviewStatus(RUN,
                com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING, "superadmin", OffsetDateTime.now(),
                null, 40, 100, "cn=all-staff,ou=groups,dc=src", OffsetDateTime.now(), OffsetDateTime.now(), false, null,
                0, 0, 0, 0, false, null, false, null, java.util.Map.of());
    }

    @Test
    void startPreview_returns202_withTheRun_andRecordsWhoAsked() throws Exception {
        given(service.startPreview(SET, "superadmin")).willReturn(runningStatus());
        mockMvc.perform(post(SETS + "/" + SET + "/preview/run").with(authentication(superadminAuth())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(RUN.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.processed").value(40))
                .andExpect(jsonPath("$.currentDn").value("cn=all-staff,ou=groups,dc=src"));
    }

    @Test
    void previewStatus_returnsTheLatestRun() throws Exception {
        given(service.previewStatus(SET)).willReturn(runningStatus());
        mockMvc.perform(get(SETS + "/" + SET + "/preview/run").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(100));
    }

    @Test
    void previewEntries_withoutACompletedRun_returns404() throws Exception {
        willThrow(new com.ldapportal.exception.ResourceNotFoundException("no completed preview"))
                .given(service).previewEntries(SET, 0, 50, true);
        mockMvc.perform(get(SETS + "/" + SET + "/preview/entries").with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    /** A preview shows attribute values, so every preview endpoint needs the manage permission. */
    @Test
    void previewEndpoints_requireManageDirectorySync() throws Exception {
        for (String name : List.of("previewStatus", "startPreview", "previewEntries")) {
            java.lang.reflect.Method m = java.util.Arrays.stream(SyncSetController.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            com.ldapportal.auth.RequiresSuperadminPermission p =
                    m.getAnnotation(com.ldapportal.auth.RequiresSuperadminPermission.class);
            org.assertj.core.api.Assertions.assertThat(p).as(name).isNotNull();
            org.assertj.core.api.Assertions.assertThat(p.value()).as(name)
                    .isEqualTo(com.ldapportal.entity.enums.SuperadminPermission.MANAGE_DIRECTORY_SYNC);
        }
    }

    @Test
    void previewEntries_defaults_returnsPage() throws Exception {
        given(service.previewEntries(SET, 0, 50, true)).willReturn(previewPage());
        mockMvc.perform(get(SETS + "/" + SET + "/preview/entries").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].action").value("MODIFY"))
                .andExpect(jsonPath("$.entries[0].changes[0].attribute").value("mail"))
                .andExpect(jsonPath("$.entries[0].changes[0].removed[0]").value("old@x"))
                .andExpect(jsonPath("$.totalEntries").value(1))
                .andExpect(jsonPath("$.runId").value(RUN.toString()));
    }

    @Test
    void previewEntries_passesPagingAndFilter() throws Exception {
        given(service.previewEntries(SET, 2, 25, false)).willReturn(previewPage());
        mockMvc.perform(get(SETS + "/" + SET + "/preview/entries?page=2&size=25&changesOnly=false")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
    }

    @Test
    void previewEntries_badSize_returns400() throws Exception {
        willThrow(new IllegalArgumentException("size must be between 1 and 200"))
                .given(service).previewEntries(SET, 0, 500, true);
        mockMvc.perform(get(SETS + "/" + SET + "/preview/entries?size=500")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previewEntries_unknownSet_returns404() throws Exception {
        willThrow(new com.ldapportal.exception.ResourceNotFoundException("Sync set not found"))
                .given(service).previewEntries(SET, 0, 50, true);
        mockMvc.perform(get(SETS + "/" + SET + "/preview/entries").with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewEntries_adminRole_returns403() throws Exception {
        mockMvc.perform(get(SETS + "/" + SET + "/preview/entries").with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }
}
