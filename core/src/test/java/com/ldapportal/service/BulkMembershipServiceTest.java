// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.csv.BulkMembershipPreviewResult;
import com.ldapportal.dto.csv.BulkMembershipPreviewRow;
import com.ldapportal.dto.csv.BulkMembershipPreviewRow.Disposition;
import com.ldapportal.dto.csv.BulkMembershipResult;
import com.ldapportal.dto.csv.BulkMembershipRowResult;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeRequest.Op;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BulkMembershipService} against an in-memory directory: real user and
 * group lookups, mocked scope checks and a mocked membership writer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BulkMembershipServiceTest {

    private static final String BASE   = "dc=example,dc=com";
    private static final String USERS  = "ou=users,dc=example,dc=com";
    private static final String GROUPS = "ou=groups,dc=example,dc=com";
    private static final String ALICE  = "uid=alice,ou=users,dc=example,dc=com";
    private static final String BOB    = "uid=bob,ou=users,dc=example,dc=com";
    private static final String DEVS   = "cn=devs,ou=groups,dc=example,dc=com";
    private static final String OPS    = "cn=ops,ou=groups,dc=example,dc=com";
    private static final String UNIQ   = "cn=uniq,ou=groups,dc=example,dc=com";
    private static final String POSIX  = "cn=posix,ou=groups,dc=example,dc=com";
    private static final String BIND   = "cn=admin,dc=example,dc=com";

    @Mock private com.ldapportal.service.EncryptionService encryptionService;
    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private PermissionService permissionService;
    @Mock private LdapOperationService operationService;

    private InMemoryDirectoryServer server;
    private LdapConnectionFactory connectionFactory;
    private BulkMembershipService service;
    private final UUID dirId = UUID.randomUUID();
    private final AuthPrincipal principal = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "op");

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.addAdditionalBindCredentials(BIND, "pw");
        config.setSchema(null);
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        server.add(new Entry(USERS, new Attribute("objectClass", "organizationalUnit"), new Attribute("ou", "users")));
        server.add(new Entry(GROUPS, new Attribute("objectClass", "organizationalUnit"), new Attribute("ou", "groups")));
        server.add(user(ALICE, "alice"));
        server.add(user(BOB, "bob"));
        server.add(new Entry(DEVS, new Attribute("objectClass", "groupOfNames"),
                new Attribute("cn", "devs"), new Attribute("member", ALICE)));
        server.add(new Entry(OPS, new Attribute("objectClass", "groupOfNames"),
                new Attribute("cn", "ops"), new Attribute("member", "uid=other,ou=users,dc=example,dc=com")));
        server.add(new Entry(UNIQ, new Attribute("objectClass", "groupOfUniqueNames"),
                new Attribute("cn", "uniq")));
        server.add(new Entry(POSIX, new Attribute("objectClass", "posixGroup"),
                new Attribute("cn", "posix"), new Attribute("memberUid", "bob")));
        server.startListening();

        when(encryptionService.decrypt(anyString())).thenReturn("pw");
        connectionFactory = new LdapConnectionFactory(encryptionService);
        var chain = new com.ldapportal.core.provisioning.ProvisioningInterceptorChain(List.of());
        var executor = new com.ldapportal.core.provisioning.PlanExecutor(connectionFactory);
        LdapUserService userService = new LdapUserService(connectionFactory, chain, executor,
                new com.ldapportal.core.provisioning.UserReadEnricherChain(List.of()));
        LdapGroupService groupService = new LdapGroupService(connectionFactory, chain, executor);

        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc()));
        when(permissionService.resolveSearchBaseDns(principal, dirId, null)).thenReturn(List.of(BASE));
        when(permissionService.isDnWithinScope(eq(principal), eq(dirId), anyString())).thenReturn(true);
        // Echo every change back as APPLIED.
        when(operationService.applyMembershipChanges(eq(dirId), eq(principal), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    List<MembershipChangeRequest.Change> changes = inv.getArgument(4);
                    List<MembershipChangeResult.Item> items = new ArrayList<>();
                    changes.forEach(c -> items.add(MembershipChangeResult.Item.applied(c)));
                    return MembershipChangeResult.of(items);
                });

        service = new BulkMembershipService(dirRepo, permissionService, userService, groupService, operationService);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        server.shutDown(true);
    }

    // ── preview ──────────────────────────────────────────────────────────────

    @Test
    void expandsAUserRowIntoOnePairPerGroup() throws Exception {
        BulkMembershipPreviewResult r = preview("user,groups\nbob,devs;ops|uniq\n");

        assertThat(r.totalPairs()).isEqualTo(3);
        assertThat(r.actionable()).isEqualTo(3);
        assertThat(r.rows()).extracting(BulkMembershipPreviewRow::groupDn).containsExactly(DEVS, OPS, UNIQ);
        assertThat(r.rows()).allSatisfy(row -> {
            assertThat(row.userDn()).isEqualTo(BOB);
            assertThat(row.rowNumber()).isEqualTo(1);
            assertThat(row.disposition()).isEqualTo(Disposition.WILL_ADD);
        });
    }

    @Test
    void acceptsOneRowPerPairAndDnsForBothColumns() throws Exception {
        BulkMembershipPreviewResult r = preview("User,Group\nbob,devs\n\"" + BOB + "\",\"" + OPS + "\"\n");

        assertThat(r.rows()).extracting(BulkMembershipPreviewRow::rowNumber).containsExactly(1, 2);
        assertThat(r.rows()).extracting(BulkMembershipPreviewRow::groupDn).containsExactly(DEVS, OPS);
        assertThat(r.rows()).extracting(BulkMembershipPreviewRow::userDn).containsOnly(BOB);
    }

    @Test
    void classifiesNoOps() throws Exception {
        BulkMembershipPreviewResult r = preview("""
                user,groups,op
                alice,devs,ADD
                alice,ops;posix,remove
                """);

        assertThat(dispositions(r)).containsExactly(
                Disposition.ALREADY_MEMBER, Disposition.NOT_A_MEMBER, Disposition.NOT_A_MEMBER);
        assertThat(r.actionable()).isZero();
    }

    @Test
    void flagsAFileThatBothAddsAndRemovesTheSameMembership() throws Exception {
        BulkMembershipPreviewResult r = preview("user,groups,op\nalice,devs,ADD\nalice,devs,REMOVE\n");

        assertThat(dispositions(r)).containsExactly(Disposition.INVALID, Disposition.INVALID);
        assertThat(r.rows().get(0).note()).contains("both adds and removes");
    }

    @Test
    void reportsUnresolvableUsersGroupsAndBadValues() throws Exception {
        BulkMembershipPreviewResult r = preview("""
                user,groups,op
                nobody,devs,
                bob,nosuchgroup,
                bob,devs,DROP
                ,devs,
                """);

        assertThat(dispositions(r)).containsExactly(
                Disposition.USER_NOT_FOUND, Disposition.GROUP_NOT_FOUND,
                Disposition.INVALID, Disposition.INVALID);
        assertThat(r.rows().get(2).note()).contains("Unknown op 'DROP'");
        assertThat(r.rows().get(3).note()).isEqualTo("No user on this row");
        assertThat(r.actionable()).isZero();
    }

    @Test
    void flagsAmbiguousUidsAndOutOfScopeGroups() throws Exception {
        server.add(user("uid=bob,ou=groups,dc=example,dc=com", "bob")); // second bob
        when(permissionService.isDnWithinScope(principal, dirId, OPS)).thenReturn(false);

        BulkMembershipPreviewResult r = preview("user,groups\nbob,devs\nalice,ops\n");

        assertThat(dispositions(r)).containsExactly(Disposition.AMBIGUOUS, Disposition.OUT_OF_SCOPE);
    }

    @Test
    void marksRepeatedPairsAsDuplicates() throws Exception {
        BulkMembershipPreviewResult r = preview("user,groups\nbob,devs\nbob,devs;ops\n");

        assertThat(dispositions(r)).containsExactly(
                Disposition.WILL_ADD, Disposition.DUPLICATE, Disposition.WILL_ADD);
        assertThat(r.rows().get(1).note()).isEqualTo("Repeats row 1");
    }

    @Test
    void readsMembershipFromUniqueMemberAndMemberUidGroups() throws Exception {
        BulkMembershipPreviewResult r = preview("user,groups,op\nbob,posix,REMOVE\nalice,posix;uniq,ADD\n");

        assertThat(dispositions(r)).containsExactly(
                Disposition.WILL_REMOVE, Disposition.WILL_ADD, Disposition.WILL_ADD);
    }

    @Test
    void rejectsFilesWithoutTheRequiredColumnsOrOverTheCap() {
        assertThatThrownBy(() -> preview("name,groups\nbob,devs\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'user' column");

        StringBuilder big = new StringBuilder("user,groups\n");
        for (int i = 0; i <= BulkMembershipService.MAX_PAIRS; i++) big.append("u").append(i).append(",g\n");
        assertThatThrownBy(() -> preview(big.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit is " + BulkMembershipService.MAX_PAIRS);
    }

    @Test
    void ignoresAnExcelByteOrderMarkOnTheHeader() throws Exception {
        BulkMembershipPreviewResult r = preview("﻿user,groups\nbob,devs\n");
        assertThat(dispositions(r)).containsExactly(Disposition.WILL_ADD);
    }

    @Test
    void previewNeverWrites() throws Exception {
        preview("user,groups\nbob,devs\n");
        verify(operationService, never()).applyMembershipChanges(any(), any(), any(), any(), any());
    }

    // ── apply ────────────────────────────────────────────────────────────────

    @Test
    void appliesEachUsersChangesInOneCallWithTheRightMemberAttribute() throws Exception {
        BulkMembershipResult r = apply("user,groups,op\nbob,devs;uniq,ADD\nbob,posix,REMOVE\nalice,ops,\n");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MembershipChangeRequest.Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(operationService).applyMembershipChanges(eq(dirId), eq(principal), eq(BOB), eq("bob"), changes.capture());
        assertThat(changes.getValue()).containsExactly(
                new MembershipChangeRequest.Change(DEVS, "member", Op.ADD),
                new MembershipChangeRequest.Change(UNIQ, "uniqueMember", Op.ADD),
                new MembershipChangeRequest.Change(POSIX, "memberUid", Op.REMOVE));
        verify(operationService).applyMembershipChanges(eq(dirId), eq(principal), eq(ALICE), eq("alice"), any());

        assertThat(r.applied()).isEqualTo(4);
        assertThat(r.failed()).isZero();
    }

    @Test
    void reportsSkipsErrorsAndQueuedApprovals() throws Exception {
        UUID approvalId = UUID.randomUUID();
        when(operationService.applyMembershipChanges(eq(dirId), eq(principal), eq(BOB), eq("bob"), any()))
                .thenAnswer(inv -> {
                    List<MembershipChangeRequest.Change> c = inv.getArgument(4);
                    return MembershipChangeResult.of(List.of(MembershipChangeResult.Item.queued(c.get(0), approvalId)));
                });

        BulkMembershipResult r = apply("user,groups\nbob,ops\nalice,devs\nnobody,devs\n");

        assertThat(r.rows()).extracting(BulkMembershipRowResult::status).containsExactly(
                BulkMembershipRowResult.Status.QUEUED_FOR_APPROVAL,
                BulkMembershipRowResult.Status.SKIPPED,
                BulkMembershipRowResult.Status.ERROR);
        assertThat(r.rows().get(0).approvalId()).isEqualTo(approvalId);
        assertThat(r.rows().get(1).message()).isEqualTo("Already a member");
        assertThat(r.queued()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        verify(operationService, never()).applyMembershipChanges(any(), any(), eq(ALICE), any(), any());
        verify(operationService, never()).applyMembershipChanges(any(), any(), isNull(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private BulkMembershipPreviewResult preview(String csv) throws Exception {
        return service.preview(dirId, principal, stream(csv));
    }

    private BulkMembershipResult apply(String csv) throws Exception {
        return service.apply(dirId, principal, stream(csv));
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Disposition> dispositions(BulkMembershipPreviewResult r) {
        return r.rows().stream().map(BulkMembershipPreviewRow::disposition).toList();
    }

    private static Entry user(String dn, String uid) {
        return new Entry(dn, new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", uid), new Attribute("cn", uid), new Attribute("sn", uid));
    }

    private DirectoryConnection dc() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(dirId);
        d.setDisplayName("test");
        d.setHost("localhost");
        d.setPort(server.getListenPort());
        d.setSslMode(SslMode.NONE);
        d.setBindDn(BIND);
        d.setBindPasswordEncrypted("enc");
        d.setBaseDn(BASE);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setPagingSize(100);
        return d;
    }
}
