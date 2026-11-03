// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.diff.DirectoryDiffRequest;
import com.ldapportal.dto.diff.DirectoryDiffRunResponse;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryDiffPairing;
import com.ldapportal.entity.enums.DirectoryDiffStatus;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.DirectoryDiffRunRepository;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A run that would fill more heap than its budget fails cleanly with a message
 * saying how to narrow it, instead of running the server out of memory.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "ldapportal.directory-diff.memory-budget-mb=1")
class DirectoryDiffMemoryBudgetTest {

    private static final String BASE = "dc=a,dc=com";
    private static final String BIND_DN = "cn=admin,dc=com";
    private static final String BIND_PASS = "adminpass";

    @MockitoBean private EncryptionService encryptionService;
    @Autowired private DirectoryConnectionRepository directoryRepo;
    @Autowired private DirectoryDiffRunRepository runRepo;
    @Autowired private DirectoryDiffService service;

    private InMemoryDirectoryServer server;
    private DirectoryConnection dir;

    @BeforeEach
    void setUp() throws Exception {
        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        runRepo.deleteAll();
        directoryRepo.deleteAll();
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig(BASE);
        cfg.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        server = new InMemoryDirectoryServer(cfg);
        server.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "a")));
        server.add(new Entry("ou=one," + BASE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "one")));
        server.add(new Entry("ou=two," + BASE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "two")));
        String big = "x".repeat(100_000);
        for (int i = 0; i < 15; i++) {
            server.add(new Entry("cn=e" + i + ",ou=one," + BASE,
                    new Attribute("objectClass", "top", "person"), new Attribute("cn", "e" + i),
                    new Attribute("sn", "e"), new Attribute("description", big)));
        }
        server.startListening();
        dir = directoryRepo.save(directory(server.getListenPort()));
    }

    @AfterEach
    void tearDown() {
        server.shutDown(true);
    }

    @Test
    void failsARunThatWouldExceedItsMemoryBudget() throws Exception {
        DirectoryDiffRunResponse run = finish(service.start(request(List.of()), principal()));
        assertThat(run.status()).isEqualTo(DirectoryDiffStatus.FAILED);
        assertThat(run.error()).contains("needs more memory than a comparison may use (about 1 MB")
                .contains("Narrow the base DN or the filter");
    }

    @Test
    void attributesThatAreNotComparedDoNotCount() throws Exception {
        // Leaving the large attribute out keeps the same entries well inside the budget.
        DirectoryDiffRunResponse run = finish(service.start(request(List.of("description")), principal()));
        assertThat(run.status()).as(run.error()).isEqualTo(DirectoryDiffStatus.COMPLETE);
    }

    private DirectoryDiffRequest request(List<String> ignore) {
        return new DirectoryDiffRequest(
                new DirectoryDiffRequest.Side(dir.getId(), "ou=one," + BASE, null, null),
                new DirectoryDiffRequest.Side(dir.getId(), "ou=two," + BASE, null, null),
                DirectoryDiffPairing.DN, null, null, ignore);
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "superadmin");
    }

    private DirectoryDiffRunResponse finish(DirectoryDiffRunResponse started) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        DirectoryDiffRunResponse run = started;
        while (run.status() == DirectoryDiffStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            run = service.get(started.id());
        }
        return run;
    }

    private static DirectoryConnection directory(int port) {
        DirectoryConnection d = new DirectoryConnection();
        d.setSlug("a");
        d.setDisplayName("a");
        d.setDirectoryType(DirectoryType.GENERIC);
        d.setHost("localhost");
        d.setPort(port);
        d.setSslMode(SslMode.NONE);
        d.setTrustAllCerts(false);
        d.setBindDn(BIND_DN);
        d.setBindPasswordEncrypted("enc-placeholder");
        d.setBaseDn(BASE);
        d.setPagingSize(100);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setEnabled(true);
        return d;
    }
}
