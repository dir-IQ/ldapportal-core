// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.IntegrityReport;
import com.ldapportal.dto.ldap.IntegrityReport.IntegrityIssue;
import com.ldapportal.dto.ldap.IntegrityReport.IssueType;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesRequest.Reference;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesResult;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesResult.Status;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.schema.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * The integrity check's broken-member report and the removal of the references
 * it reports, against an in-memory server with the standard schema (so a
 * {@code groupOfNames} must keep a {@code member}).
 */
@ExtendWith(MockitoExtension.class)
class IntegrityCheckServiceTest {

    private static final String BASE = "dc=example,dc=com";
    private static final String STAFF = "cn=staff," + BASE;
    private static final String ADMINS = "cn=admins," + BASE;
    private static final String BIND_DN = "cn=admin," + BASE;
    private static final String BIND_PASS = "adminpass";

    @Mock private EncryptionService encryptionService;

    private InMemoryDirectoryServer server;
    private LdapConnectionFactory connectionFactory;
    private IntegrityCheckService service;
    private DirectoryConnection dc;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        // The SDK's standard schema makes groupOfNames' member optional; a
        // strict group class stands in for servers where it's required.
        Entry schema = Schema.getDefaultStandardSchema().getSchemaEntry().duplicate();
        schema.addAttribute("objectClasses", "( 1.3.6.1.4.1.32473.1.1 NAME 'strictGroup' SUP top STRUCTURAL "
                + "MUST ( cn $ member ) )");
        config.setSchema(new Schema(schema));
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        person("alice");
        server.add(new Entry(STAFF, new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "staff"),
                new Attribute("member", "uid=alice," + BASE, "uid=ghost," + BASE, "uid=gone," + BASE)));
        // Its only member is broken, and the group can't be left without one.
        server.add(new Entry(ADMINS, new Attribute("objectClass", "top", "strictGroup"),
                new Attribute("cn", "admins"), new Attribute("member", "uid=ghost," + BASE)));
        server.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        service = new IntegrityCheckService(connectionFactory, new LdapBrowseService(connectionFactory));

        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("integrity");
        dc.setHost("localhost");
        dc.setPort(server.getListenPort());
        dc.setSslMode(SslMode.NONE);
        dc.setBindDn(BIND_DN);
        dc.setBindPasswordEncrypted("enc-placeholder");
        dc.setBaseDn(BASE);
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(3);
        dc.setPoolConnectTimeoutSeconds(5);
        dc.setPoolResponseTimeoutSeconds(10);
        dc.setPagingSize(100);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        server.shutDown(true);
    }

    private void person(String uid) throws Exception {
        server.add(new Entry("uid=" + uid + "," + BASE,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", uid), new Attribute("cn", uid), new Attribute("sn", uid)));
    }

    private List<String> members(String group) throws Exception {
        return List.of(server.getEntry(group).getAttributeValues("member"));
    }

    @Test
    void check_reportsEachBrokenReference_withItsAttributeAndValue() {
        IntegrityReport report = service.runChecks(dc, null, EnumSet.of(IssueType.BROKEN_MEMBER));

        assertThat(report.issues()).extracting(IntegrityIssue::dn, IntegrityIssue::attribute, IntegrityIssue::value)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(STAFF, "member", "uid=ghost," + BASE),
                        org.assertj.core.groups.Tuple.tuple(STAFF, "member", "uid=gone," + BASE),
                        org.assertj.core.groups.Tuple.tuple(ADMINS, "member", "uid=ghost," + BASE));
    }

    @Test
    void check_comparesMembersAsDns_soCaseOrSpacingAloneIsntBroken() throws Exception {
        server.modify(STAFF, new com.unboundid.ldap.sdk.Modification(
                com.unboundid.ldap.sdk.ModificationType.REPLACE, "member", "UID=Alice, DC=Example, DC=Com"));
        // (Replacing staff's members also drops its ghost and gone references.)

        IntegrityReport report = service.runChecks(dc, null, EnumSet.of(IssueType.BROKEN_MEMBER));

        // Only admins' ghost is left: alice's differently-formatted DN still names her.
        assertThat(report.issues()).extracting(IntegrityIssue::dn).containsExactly(ADMINS);
    }

    @Test
    void remove_takesBrokenReferencesOut_inOneModifyPerGroup() throws Exception {
        RemoveBrokenReferencesResult result = service.removeBrokenReferences(dc, List.of(
                new Reference(STAFF, "member", "uid=ghost," + BASE),
                new Reference(STAFF, "member", "uid=gone," + BASE)));

        assertThat(result.removed()).isEqualTo(2);
        assertThat(result.outcomes()).extracting(RemoveBrokenReferencesResult.Outcome::status)
                .containsExactly(Status.REMOVED, Status.REMOVED);
        assertThat(members(STAFF)).containsExactly("uid=alice," + BASE);
    }

    @Test
    void remove_keepsAReferenceWhoseEntryExistsNow() throws Exception {
        person("ghost"); // re-created since the report

        RemoveBrokenReferencesResult result = service.removeBrokenReferences(dc, List.of(
                new Reference(STAFF, "member", "uid=ghost," + BASE),
                new Reference(STAFF, "member", "uid=gone," + BASE)));

        assertThat(result.outcomes()).extracting(RemoveBrokenReferencesResult.Outcome::status)
                .containsExactly(Status.KEPT_ENTRY_EXISTS, Status.REMOVED);
        assertThat(members(STAFF)).containsExactlyInAnyOrder("uid=alice," + BASE, "uid=ghost," + BASE);
    }

    @Test
    void remove_skipsAReferenceTheGroupNoLongerHolds_andAGroupThatIsGone() throws Exception {
        RemoveBrokenReferencesResult result = service.removeBrokenReferences(dc, List.of(
                new Reference(STAFF, "member", "uid=nobody," + BASE),
                new Reference("cn=deleted," + BASE, "member", "uid=ghost," + BASE)));

        assertThat(result.removed()).isZero();
        assertThat(result.outcomes()).extracting(RemoveBrokenReferencesResult.Outcome::status)
                .containsExactly(Status.ALREADY_GONE, Status.ALREADY_GONE);
    }

    @Test
    void remove_matchesTheStoredValueAsADn() throws Exception {
        RemoveBrokenReferencesResult result = service.removeBrokenReferences(dc, List.of(
                new Reference(STAFF, "MEMBER", "UID=Ghost, DC=Example, DC=Com")));

        assertThat(result.outcomes().get(0).status()).isEqualTo(Status.REMOVED);
        assertThat(members(STAFF)).doesNotContain("uid=ghost," + BASE);
    }

    @Test
    void remove_failsAGroupThatWouldBeLeftWithoutAMember_andLeavesItUnchanged() throws Exception {
        RemoveBrokenReferencesResult result = service.removeBrokenReferences(dc, List.of(
                new Reference(ADMINS, "member", "uid=ghost," + BASE),
                new Reference(STAFF, "member", "uid=ghost," + BASE)));

        assertThat(result.outcomes().get(0).status()).isEqualTo(Status.FAILED);
        assertThat(result.outcomes().get(0).message()).contains("required member attribute");
        assertThat(members(ADMINS)).containsExactly("uid=ghost," + BASE);
        // Other groups are unaffected by one group's failure.
        assertThat(result.outcomes().get(1).status()).isEqualTo(Status.REMOVED);
        assertThat(result.removed()).isEqualTo(1);
    }

    @Test
    void remove_refusesAnyOtherAttribute() {
        assertThatThrownBy(() -> service.removeBrokenReferences(dc, List.of(
                new Reference(STAFF, "cn", "staff"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
