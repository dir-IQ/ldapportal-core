// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JPA optimistic-lock timing the ETag layer depends on: the
 * {@code @Version} counter is only incremented when the UPDATE is flushed,
 * <em>not</em> on a plain {@code save()}. An update endpoint builds its
 * response (and ETag) before the transaction commits, so it must
 * {@code saveAndFlush} — otherwise it would hand back the pre-increment
 * version and the client's next {@code If-Match} would spuriously 412.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DirectoryConnectionVersionFlushTest {

    @Autowired private DirectoryConnectionRepository repo;
    @Autowired private EntityManager em;

    @Test
    void plainSave_doesNotSurfaceIncrement_butSaveAndFlushDoes() {
        DirectoryConnection inserted = repo.saveAndFlush(build());
        assertThat(inserted.getVersion()).isZero();   // insert starts at 0
        em.clear();

        DirectoryConnection fetched = repo.findById(inserted.getId()).orElseThrow();
        fetched.setDisplayName("changed-name");
        // Plain save leaves the in-memory version at its pre-update value...
        DirectoryConnection afterSave = repo.save(fetched);
        assertThat(afterSave.getVersion()).isZero();
        // ...only flushing the UPDATE surfaces the increment, which is what an
        // update response needs so its ETag matches the now-stored version.
        repo.flush();
        assertThat(afterSave.getVersion()).isEqualTo(1L);
    }

    private static DirectoryConnection build() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.GENERIC);
        dc.setDisplayName("flush-probe");
        dc.setHost("ldap.example.com");
        dc.setPort(389);
        dc.setSslMode(SslMode.NONE);
        dc.setBindDn("cn=x");
        dc.setBindPasswordEncrypted("enc");
        dc.setBaseDn("dc=x");
        dc.setPagingSize(500);
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(2);
        dc.setPoolConnectTimeoutSeconds(10);
        dc.setPoolResponseTimeoutSeconds(30);
        return dc;
    }
}
