// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.profile.ApprovalConfigRequest;
import com.ldapportal.dto.profile.CreateProfileRequest;
import com.ldapportal.dto.profile.LifecyclePolicyRequest;
import com.ldapportal.dto.profile.ProfileExportDocument;
import com.ldapportal.dto.profile.ProfileExportDocument.Approval;
import com.ldapportal.dto.profile.ProfileExportDocument.Lifecycle;
import com.ldapportal.dto.profile.ProfileExportDocument.ProfileDefinition;
import com.ldapportal.dto.profile.ProfileImportRequest;
import com.ldapportal.dto.profile.ProfileImportRequest.Mode;
import com.ldapportal.dto.profile.ProfileImportResult;
import com.ldapportal.dto.profile.ProfileImportResult.DnChange;
import com.ldapportal.dto.profile.ProfileResponse;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProfileApprovalConfig;
import com.ldapportal.entity.ProfileAttributeConfig;
import com.ldapportal.entity.ProfileGroupAssignment;
import com.ldapportal.entity.ProfileLifecyclePolicy;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.ApproverMode;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ExpiryAction;
import com.ldapportal.entity.enums.InputType;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ProfileApprovalConfigRepository;
import com.ldapportal.repository.ProfileAttributeConfigRepository;
import com.ldapportal.repository.ProfileGroupAssignmentRepository;
import com.ldapportal.repository.ProfileLifecyclePolicyRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProfileExportImportServiceTest {

    @Mock private ProvisioningProfileService profileService;
    @Mock private ProvisioningProfileRepository profileRepo;
    @Mock private ProfileAttributeConfigRepository attrConfigRepo;
    @Mock private ProfileGroupAssignmentRepository groupAssignmentRepo;
    @Mock private ProfileLifecyclePolicyRepository lifecycleRepo;
    @Mock private ProfileApprovalConfigRepository approvalConfigRepo;
    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private AuditService auditService;

    private ProfileExportImportService service;

    private DirectoryConnection devDir;
    private DirectoryConnection prodDir;
    private final AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");

    @BeforeEach
    void setUp() {
        service = new ProfileExportImportService(profileService, profileRepo, attrConfigRepo,
                groupAssignmentRepo, lifecycleRepo, approvalConfigRepo, dirRepo, auditService);
        devDir = dir("Dev", "dc=dev,dc=corp");
        prodDir = dir("Prod", "dc=prod,dc=corp");
        lenient().when(dirRepo.findById(prodDir.getId())).thenReturn(Optional.of(prodDir));
    }

    private static DirectoryConnection dir(String name, String baseDn) {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName(name);
        d.setBaseDn(baseDn);
        return d;
    }

    private static ProfileResponse response(UUID id, String name) {
        ProfileResponse r = mock(ProfileResponse.class);
        lenient().when(r.id()).thenReturn(id);
        lenient().when(r.name()).thenReturn(name);
        return r;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    @Test
    void export_carriesTheWholeProfileByName_butNoPeople() {
        ProvisioningProfile linked = new ProvisioningProfile();
        linked.setName("Base groups");
        ProvisioningProfile p = new ProvisioningProfile();
        p.setId(UUID.randomUUID());
        p.setDirectory(devDir);
        p.setName("Employees");
        p.setTargetUserDn("ou=people,dc=dev,dc=corp");
        p.setObjectClassNames(new ArrayList<>(List.of("inetOrgPerson")));
        p.setRdnAttribute("uid");
        p.setAdditionalProfiles(new HashSet<>(Set.of(linked)));
        given(profileRepo.findByIdAndDirectoryId(p.getId(), devDir.getId())).willReturn(Optional.of(p));

        ProfileAttributeConfig mail = new ProfileAttributeConfig();
        mail.setAttributeName("mail");
        mail.setInputType(InputType.TEXT);
        mail.setRequiredOnCreate(true);
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(p.getId())).willReturn(List.of(mail));
        ProfileGroupAssignment g = new ProfileGroupAssignment();
        g.setGroupDn("cn=staff,ou=groups,dc=dev,dc=corp");
        g.setMemberAttribute("member");
        given(groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(p.getId())).willReturn(List.of(g));
        ProfileLifecyclePolicy lc = new ProfileLifecyclePolicy();
        lc.setExpiresAfterDays(90);
        lc.setOnExpiryAction(ExpiryAction.MOVE);
        lc.setOnExpiryMoveDn("ou=expired,dc=dev,dc=corp");
        given(lifecycleRepo.findByProfileId(p.getId())).willReturn(Optional.of(lc));
        ProfileApprovalConfig ac = new ProfileApprovalConfig();
        ac.setRequireApproval(true);
        ac.setApproverMode(ApproverMode.DATABASE);
        ac.setAutoEscalateDays(3);
        ac.setEscalationAccount(new Account());
        given(approvalConfigRepo.findByProfileId(p.getId())).willReturn(Optional.of(ac));

        ProfileExportDocument doc = service.export(devDir.getId(), p.getId());

        assertThat(doc.formatVersion()).isEqualTo(ProfileExportDocument.FORMAT_VERSION);
        assertThat(doc.kind()).isEqualTo(ProfileExportDocument.KIND);
        assertThat(doc.source().baseDn()).isEqualTo("dc=dev,dc=corp");
        assertThat(doc.source().directoryName()).isEqualTo("Dev");
        ProfileDefinition def = doc.profile();
        assertThat(def.name()).isEqualTo("Employees");
        assertThat(def.additionalProfileNames()).containsExactly("Base groups");
        assertThat(def.attributeConfigs()).extracting(CreateProfileRequest.AttributeConfigEntry::attributeName)
                .containsExactly("mail");
        assertThat(def.groupAssignments()).extracting(CreateProfileRequest.GroupAssignmentEntry::groupDn)
                .containsExactly("cn=staff,ou=groups,dc=dev,dc=corp");
        assertThat(def.lifecycle().onExpiryMoveDn()).isEqualTo("ou=expired,dc=dev,dc=corp");
        // The approval settings travel; who approves and escalates doesn't.
        assertThat(def.approval()).isEqualTo(new Approval(true, ApproverMode.DATABASE, null, 3));
    }

    @Test
    void theFileRoundTripsThroughJson() throws Exception {
        ProfileExportDocument doc = sampleDoc();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ProfileExportDocument back = json.readValue(json.writeValueAsString(doc), ProfileExportDocument.class);

        assertThat(back).usingRecursiveComparison().ignoringFields("exportedAt").isEqualTo(doc);
    }

    // ── Import: new profile ───────────────────────────────────────────────────

    private static ProfileExportDocument sampleDoc() {
        ProfileDefinition def = new ProfileDefinition(
                "Employees", "staff", "#2563eb",
                "ou=people,dc=dev,dc=corp", "ou=groups, DC=Dev, dc=corp", List.of("inetOrgPerson"),
                "uid", false, "uid={uid},ou=people,dc=dev,dc=corp", 12, "Identity", 0,
                false, 20, true, true, true, false, null, false, "OPERATOR_ENTERED",
                false, false, List.of("Base groups", "Gone"),
                List.of(new CreateProfileRequest.AttributeConfigEntry("uid", "User ID", "TEXT", true, true, false,
                        false, false, null, null, null, null, null, null, null, "Identity", 6, false,
                        null, null, null, null, null, null)),
                List.of(new CreateProfileRequest.GroupAssignmentEntry("cn=staff,ou=groups,dc=dev,dc=corp", "member"),
                        new CreateProfileRequest.GroupAssignmentEntry("cn=global,o=shared", "member")),
                new Lifecycle(90, 2, 30, ExpiryAction.MOVE, "ou=expired,dc=dev,dc=corp", true, true, 7),
                new Approval(true, ApproverMode.DATABASE, null, 3));
        return new ProfileExportDocument(ProfileExportDocument.FORMAT_VERSION, ProfileExportDocument.KIND,
                java.time.OffsetDateTime.now(),
                new ProfileExportDocument.Source("Dev", "dc=dev,dc=corp", "Employees"), def);
    }

    private ProfileImportRequest create(boolean dryRun) {
        return new ProfileImportRequest(sampleDoc(), Mode.CREATE, null, null, true, null, null, false, dryRun);
    }

    @Test
    void importAsNew_createsADisabledProfileWithDnsMovedToTheTargetBase() {
        UUID baseId = UUID.randomUUID();
        ProvisioningProfile base = new ProvisioningProfile();
        base.setId(baseId);
        base.setName("Base groups");
        given(profileRepo.findAllByDirectoryIdOrderByNameAsc(prodDir.getId())).willReturn(List.of(base));
        UUID newId = UUID.randomUUID();
        ProfileResponse created = response(newId, "Employees");
        given(profileService.create(eq(prodDir.getId()), any(CreateProfileRequest.class), eq(false), isNull()))
                .willReturn(created);
        given(profileService.get(prodDir.getId(), newId)).willReturn(created);

        ProfileImportResult result = service.importProfile(prodDir.getId(), create(false), admin);

        ArgumentCaptor<CreateProfileRequest> req = ArgumentCaptor.forClass(CreateProfileRequest.class);
        verify(profileService).create(eq(prodDir.getId()), req.capture(), eq(false), isNull());
        CreateProfileRequest c = req.getValue();
        assertThat(c.enabled()).isFalse();
        assertThat(c.name()).isEqualTo("Employees");
        assertThat(c.targetUserDn()).isEqualTo("ou=people,dc=prod,dc=corp");
        // Case and spacing differences in the base still match.
        assertThat(c.targetGroupDn()).isEqualTo("ou=groups,dc=prod,dc=corp");
        assertThat(c.dnTemplate()).isEqualTo("uid={uid},ou=people,dc=prod,dc=corp");
        assertThat(c.groupAssignments()).extracting(CreateProfileRequest.GroupAssignmentEntry::groupDn)
                .containsExactly("cn=staff,ou=groups,dc=prod,dc=corp", "cn=global,o=shared");
        assertThat(c.additionalProfileIds()).containsExactly(baseId);
        assertThat(c.attributeConfigs()).hasSize(1);

        verify(profileService).setLifecyclePolicy(eq(newId), eq(new LifecyclePolicyRequest(
                90, 2, 30, ExpiryAction.MOVE, "ou=expired,dc=prod,dc=corp", true, true, 7)));
        verify(profileService).setApprovalConfig(eq(newId),
                eq(new ApprovalConfigRequest(true, ApproverMode.DATABASE, null, 3, null)));

        assertThat(result.profile()).isSameAs(created);
        assertThat(result.dnChanges()).extracting(DnChange::field).containsExactly(
                "Target user OU", "Target group OU", "DN template", "Group assignment",
                "Lifecycle: move on expiry to");
        assertThat(result.warnings()).anyMatch(w -> w.contains("\"Gone\""))
                .anyMatch(w -> w.contains("approvers"))
                .anyMatch(w -> w.contains("disabled"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(admin), eq(prodDir.getId()), eq(AuditAction.PROFILE_CREATE), isNull(),
                detail.capture());
        assertThat(detail.getValue()).containsEntry("operation", "import")
                .containsEntry("sourceName", "Employees").containsEntry("sourceDirectoryName", "Dev");
    }

    @Test
    void importAsNew_dryRun_reportsChangesAndSavesNothing() {
        ProfileImportResult result = service.importProfile(prodDir.getId(), create(true), admin);

        assertThat(result.profile()).isNull();
        assertThat(result.dnChanges()).isNotEmpty();
        verify(profileService, never()).create(any(), any(), anyBoolean(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void importAsNew_withoutRewrite_keepsDnsAndHonoursANewName() {
        ProfileResponse created = response(UUID.randomUUID(), "Employees (prod)");
        given(profileService.create(any(), any(), anyBoolean(), any())).willReturn(created);
        ProfileImportRequest req = new ProfileImportRequest(sampleDoc(), Mode.CREATE, "Employees (prod)", null,
                false, null, null, true, false);

        ProfileImportResult result = service.importProfile(prodDir.getId(), req, null);

        ArgumentCaptor<CreateProfileRequest> c = ArgumentCaptor.forClass(CreateProfileRequest.class);
        verify(profileService).create(eq(prodDir.getId()), c.capture(), eq(true), isNull());
        assertThat(c.getValue().name()).isEqualTo("Employees (prod)");
        assertThat(c.getValue().targetUserDn()).isEqualTo("ou=people,dc=dev,dc=corp");
        assertThat(result.dnChanges()).isEmpty();
    }

    @Test
    void refusesAFileThatIsNotAProfileExport() {
        ProfileExportDocument d = sampleDoc();
        ProfileExportDocument wrong = new ProfileExportDocument(1, "something-else", null, null, d.profile());

        assertThatThrownBy(() -> service.importProfile(prodDir.getId(),
                new ProfileImportRequest(wrong, Mode.CREATE, null, null, false, null, null, false, false), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isn't a provisioning profile export");
    }

    @Test
    void refusesAFileFromANewerFormat() {
        ProfileExportDocument d = sampleDoc();
        ProfileExportDocument newer = new ProfileExportDocument(ProfileExportDocument.FORMAT_VERSION + 1,
                ProfileExportDocument.KIND, null, d.source(), d.profile());

        assertThatThrownBy(() -> service.importProfile(prodDir.getId(),
                new ProfileImportRequest(newer, Mode.CREATE, null, null, false, null, null, false, false), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format version");
    }

    // ── Import: form only ─────────────────────────────────────────────────────

    @Test
    void importForm_replacesTheTargetsFormOnly() {
        ProvisioningProfile target = new ProvisioningProfile();
        target.setId(UUID.randomUUID());
        target.setName("Employees");
        target.setDirectory(prodDir);
        given(profileRepo.findByIdAndDirectoryId(target.getId(), prodDir.getId())).willReturn(Optional.of(target));
        given(profileService.replaceForm(eq(target), eq(12), eq("Identity"), eq(0), any())).willReturn(target);
        ProfileImportRequest req = new ProfileImportRequest(sampleDoc(), Mode.REPLACE_FORM, null, target.getId(),
                false, null, null, false, false);

        service.importProfile(prodDir.getId(), req, admin);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CreateProfileRequest.AttributeConfigEntry>> form = ArgumentCaptor.forClass(List.class);
        verify(profileService).replaceForm(eq(target), eq(12), eq("Identity"), eq(0), form.capture());
        assertThat(form.getValue()).extracting(CreateProfileRequest.AttributeConfigEntry::attributeName)
                .containsExactly("uid");
        verify(profileService, never()).create(any(), any(), anyBoolean(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(admin), eq(prodDir.getId()), eq(AuditAction.PROFILE_UPDATE), isNull(),
                detail.capture());
        assertThat(detail.getValue()).containsEntry("operation", "import-form");
    }

    @Test
    void importForm_needsATarget() {
        assertThatThrownBy(() -> service.importProfile(prodDir.getId(),
                new ProfileImportRequest(sampleDoc(), Mode.REPLACE_FORM, null, null, false, null, null, false, false),
                admin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Base-DN rewrite ───────────────────────────────────────────────────────

    @Test
    void rewrite_movesOnlyValuesUnderTheBase() {
        String from = "dc=dev,dc=corp";
        String to = "dc=prod,dc=corp";
        assertThat(ProfileExportImportService.DnRewrite.rewrite("dc=dev,dc=corp", from, to)).isEqualTo(to);
        assertThat(ProfileExportImportService.DnRewrite.rewrite("ou=a, DC=DEV, dc=corp", from, to))
                .isEqualTo("ou=a,dc=prod,dc=corp");
        assertThat(ProfileExportImportService.DnRewrite.rewrite("ou=a,dc=devx,dc=corp", from, to))
                .isEqualTo("ou=a,dc=devx,dc=corp");
        assertThat(ProfileExportImportService.DnRewrite.rewrite("cn=x,o=other", from, to)).isEqualTo("cn=x,o=other");
    }
}
