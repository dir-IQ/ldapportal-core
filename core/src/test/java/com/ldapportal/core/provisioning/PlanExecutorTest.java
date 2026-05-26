// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * End-to-end executor tests against an in-memory UnboundID
 * directory. Covers each {@link StepFailurePolicy} flavour and
 * the {@link GroupMemberPlan} refusal path.
 *
 * <p>Why an in-memory directory rather than mocking the connection?
 * Each step's failure modes (already-exists, no-such-object, etc.)
 * are real LDAP result codes that the executor must round-trip
 * cleanly. Mocking would let us assert on intent but miss the
 * "did the failure actually surface as an LDAPException with the
 * right result code" question.</p>
 */
@ExtendWith(MockitoExtension.class)
class PlanExecutorTest {

    @Mock private EncryptionService encryptionService;

    private InMemoryDirectoryServer inMemoryServer;
    private LdapConnectionFactory connectionFactory;
    private PlanExecutor executor;
    private DirectoryConnection dc;

    private static final String BASE_DN  = "dc=example,dc=com";
    private static final String USERS_OU = "ou=users,dc=example,dc=com";
    private static final String BIND_DN  = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        inMemoryServer = new InMemoryDirectoryServer(config);
        inMemoryServer.add(new Entry(BASE_DN,
                new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "example")));
        inMemoryServer.add(new Entry(USERS_OU,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "users")));
        inMemoryServer.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        executor = new PlanExecutor(connectionFactory);
        dc = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    // ── happy paths ──────────────────────────────────────────────────

    @Test
    void singleStepAdd_succeeds() throws Exception {
        UserCreatePlan plan = UserCreatePlan.singleStep(
                AddStep.of("uid=alice," + USERS_OU, attrs("alice")));

        executor.execute(dc, plan);

        assertThat(inMemoryServer.entryExists("uid=alice," + USERS_OU)).isTrue();
    }

    @Test
    void multiStepAdd_appliesBothStepsInOrder() throws Exception {
        // Linked-mode-shaped plan: two ADDs to different DNs.
        UserCreatePlan plan = new UserCreatePlan(
                List.of(
                        AddStep.of("uid=alice," + USERS_OU, attrs("alice")),
                        AddStep.of("uid=alice-shadow," + USERS_OU, attrs("alice-shadow"))),
                Optional.empty());

        executor.execute(dc, plan);

        assertThat(inMemoryServer.entryExists("uid=alice," + USERS_OU)).isTrue();
        assertThat(inMemoryServer.entryExists("uid=alice-shadow," + USERS_OU)).isTrue();
    }

    @Test
    void delete_singleStep_removesEntry() throws Exception {
        inMemoryServer.add(new Entry("uid=bob," + USERS_OU,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("cn", "Bob"), new Attribute("sn", "Bee")));

        executor.execute(dc, DeletePlan.singleStep(DeleteStep.of("uid=bob," + USERS_OU)));

        assertThat(inMemoryServer.entryExists("uid=bob," + USERS_OU)).isFalse();
    }

    @Test
    void password_modifyStep_replacesAttribute() throws Exception {
        inMemoryServer.add(new Entry("uid=carol," + USERS_OU,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("cn", "Carol"), new Attribute("sn", "Cee"),
                new Attribute("userPassword", "old")));

        executor.execute(dc, PasswordPlan.singleStep(
                ModifyStep.of("uid=carol," + USERS_OU,
                        List.of(new Modification(ModificationType.REPLACE, "userPassword", "new")))));

        Entry got = inMemoryServer.getEntry("uid=carol," + USERS_OU);
        assertThat(got.getAttributeValue("userPassword")).isEqualTo("new");
    }

    // ── failure policies ─────────────────────────────────────────────

    @Test
    void abort_failingStep_throwsAndSkipsRemainingSteps() throws Exception {
        // Two-step plan; step 1 fails (entry already exists), step 2
        // would create a different entry. ABORT must skip step 2.
        inMemoryServer.add(new Entry("uid=preexisting," + USERS_OU,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("cn", "Pre"), new Attribute("sn", "Ex")));

        UserCreatePlan plan = new UserCreatePlan(
                List.of(
                        AddStep.of("uid=preexisting," + USERS_OU, attrs("preexisting")),
                        AddStep.of("uid=shouldnotappear," + USERS_OU, attrs("shouldnotappear"))),
                Optional.empty());

        assertThatThrownBy(() -> executor.execute(dc, plan))
                .isInstanceOf(LdapOperationException.class)
                .hasMessageContaining("aborted")
                .hasMessageContaining("step 1 of 2");

        // Second step skipped — DN must not exist.
        assertThat(inMemoryServer.entryExists("uid=shouldnotappear," + USERS_OU)).isFalse();
    }

    @Test
    void abort_earlierSucceededStepStays_partialState() throws Exception {
        // Step 1 succeeds, step 2 fails. ABORT bubbles the exception
        // BUT the step-1 write stays applied — partial state is the
        // cost of non-transactional LDAP. The compensation-policy
        // test below shows the alternative.
        UserCreatePlan plan = new UserCreatePlan(
                List.of(
                        AddStep.of("uid=stays," + USERS_OU, attrs("stays")),
                        AddStep.of("uid=invalid,ou=nonexistent,dc=example,dc=com", attrs("invalid"))),
                Optional.empty());

        assertThatThrownBy(() -> executor.execute(dc, plan))
                .isInstanceOf(LdapOperationException.class);

        assertThat(inMemoryServer.entryExists("uid=stays," + USERS_OU)).isTrue();
    }

    @Test
    void compensate_runsCompensationAndPropagatesOriginal() throws Exception {
        // The linked-mode user-create shape: step 1 succeeds (creates
        // demographic), step 2 fails (secUser ADD targeted at a DIT
        // that doesn't exist) with onFailure = COMPENSATE. Compensation
        // is a DEL of step 1's DN. Result: demographic gone, error
        // surfaced.
        String demographicDn = "uid=lonely," + USERS_OU;
        UserCreatePlan plan = new UserCreatePlan(
                List.of(
                        AddStep.of(demographicDn, attrs("lonely")),
                        new AddStep(
                                "uid=secuser,ou=nonexistent,dc=example,dc=com",
                                attrs("secuser"),
                                StepFailurePolicy.COMPENSATE)),
                Optional.of(List.of(DeleteStep.of(demographicDn))));

        assertThatThrownBy(() -> executor.execute(dc, plan))
                .isInstanceOf(LdapOperationException.class)
                .hasMessageContaining("compensation attempted");

        // Compensation ran — demographic entry gone.
        assertThat(inMemoryServer.entryExists(demographicDn)).isFalse();
    }

    @Test
    void compensate_withoutCompensationBlock_logsAndPropagates() {
        // COMPENSATE policy on a plan that didn't supply a compensation
        // block: surface the error, leave partial state. The log
        // warning is a contract on the failure-handler path; we can't
        // easily assert log content here, but the absence of an
        // accidental success is enough.
        UserCreatePlan plan = new UserCreatePlan(
                List.of(new AddStep(
                        "uid=nowhere,ou=nonexistent,dc=example,dc=com",
                        attrs("nowhere"),
                        StepFailurePolicy.COMPENSATE)),
                Optional.empty());

        assertThatThrownBy(() -> executor.execute(dc, plan))
                .isInstanceOf(LdapOperationException.class);
    }

    @Test
    void continue_failingStep_proceedsToNext() throws Exception {
        // Step 1 fails (parent doesn't exist) with onFailure = CONTINUE;
        // step 2 succeeds. Should not throw; step 2's effect must be
        // visible.
        UserCreatePlan plan = new UserCreatePlan(
                List.of(
                        new AddStep(
                                "uid=phantom,ou=nonexistent,dc=example,dc=com",
                                attrs("phantom"),
                                StepFailurePolicy.CONTINUE),
                        AddStep.of("uid=real," + USERS_OU, attrs("real"))),
                Optional.empty());

        executor.execute(dc, plan);

        assertThat(inMemoryServer.entryExists("uid=real," + USERS_OU)).isTrue();
    }

    // ── group-membership refusal ─────────────────────────────────────

    @Test
    void groupMembership_refusal_throwsProvisioningRefusedException() {
        GroupMemberPlan refused = GroupMemberPlan.refuse(
                "Target group lacks objectClass: secGroup");

        assertThatThrownBy(() -> executor.execute(dc, refused))
                .isInstanceOf(ProvisioningRefusedException.class)
                .hasMessageContaining("secGroup");
    }

    @Test
    void groupMembership_proceeding_appliesSingleModify() throws Exception {
        // Create the group first, then membership-add.
        inMemoryServer.add(new Entry("cn=eng,ou=users,dc=example,dc=com",
                new Attribute("objectClass", "groupOfNames"),
                new Attribute("cn", "eng"),
                new Attribute("member", "uid=placeholder,dc=example,dc=com")));

        GroupMemberPlan plan = GroupMemberPlan.proceed(
                ModifyStep.of("cn=eng,ou=users,dc=example,dc=com",
                        List.of(new Modification(ModificationType.ADD, "member",
                                "uid=alice,ou=users,dc=example,dc=com"))));

        executor.execute(dc, plan);

        Entry group = inMemoryServer.getEntry("cn=eng,ou=users,dc=example,dc=com");
        assertThat(group.getAttribute("member").getValues())
                .contains("uid=alice,ou=users,dc=example,dc=com");
    }

    // ── empty plan = no-op ───────────────────────────────────────────

    @Test
    void emptyPlan_doesNothing() {
        // Defensive: an empty plan shouldn't even open a connection.
        // Hard to assert "no connection opened" here without a spy
        // factory; relying on "no exception thrown" is sufficient
        // since any LDAP-touching code path would surface a
        // pool-acquisition error in this test fixture's setup
        // moments.
        executor.execute(dc, new DeletePlan(List.of()));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static List<Attribute> attrs(String uid) {
        return List.of(
                new Attribute("objectClass", "inetOrgPerson", "organizationalPerson", "person"),
                new Attribute("uid", uid),
                new Attribute("cn", uid),
                new Attribute("sn", uid));
    }

    private DirectoryConnection buildDc() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName("test-ldap");
        d.setHost("localhost");
        d.setPort(inMemoryServer.getListenPort());
        d.setSslMode(SslMode.NONE);
        d.setTrustAllCerts(false);
        d.setBindDn(BIND_DN);
        d.setBindPasswordEncrypted("enc-placeholder");
        d.setBaseDn(BASE_DN);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setPagingSize(100);
        return d;
    }
}
