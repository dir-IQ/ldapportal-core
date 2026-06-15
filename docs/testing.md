# Testing guide

Conventions and how-tos for the LDAP Portal test suites. Frontend conventions
live in [`frontend/README.md`](../frontend/README.md) (Vitest + Vue Test Utils);
this doc covers backend conventions.

## Using the LDAP test fixture

For backend tests that touch LDAP, use the `@OpenLdapTest` annotation to spin
up a real `bitnamilegacy/openldap:2.6.10-debian-12-r4` container instead of
mocking `UnboundIDLdapInterface`. The container is a JVM singleton — startup
cost is paid once across all annotated test classes — and per-class isolation
comes from each class getting its own OU subtree under
`ou=<TestClassName>,ou=test,dc=test,dc=local`.

### Basic usage

```java
import com.ldapportal.testsupport.ldap.LdapFixture;
import com.ldapportal.testsupport.ldap.OpenLdapTest;
import org.junit.jupiter.api.Test;

@OpenLdapTest
class MyServiceIntegrationTest {

    @Test
    void something(LdapFixture fix) {
        String aliceDn = fix.given()
            .user("alice")
            .email("alice@example.com")
            .employeeId("E-42")
            .inGroup("engineers")
            .done();

        // ... call your service against real LDAP, assert behavior
    }
}
```

`LdapFixture` is parameter-injected by the JUnit extension. It writes under
the per-class OU subtree automatically.

### When to use mock vs integration

- **Mock (existing patterns):** test the service's handling of SDK
  responses — what does the service do when the SDK returns an entry shaped a
  specific way? Fast, isolated, no Docker.
- **Integration (this fixture):** test the service against a real LDAP
  server — does the filter actually escape `*`, does schema validation pass,
  does referral handling work? Slower (~5s container boot per JVM), catches
  protocol-level bugs mocks miss.

Both have value. Don't migrate existing mock tests to integration; add
integration tests alongside them when you specifically need to verify
protocol-level behavior.

### Builder methods

| Method | Purpose |
|---|---|
| `.user(cn)` | Required. Sets `cn`, `sn` (= cn), `uid` (= cn lowercased). |
| `.email(e)` | Sets `mail`. |
| `.employeeId(id)` | Sets `employeeNumber`. |
| `.inGroup(cn)` | Adds the user as a `member` of `cn=<group>,<class-OU>`; creates the group on first call. |
| `.disabled()` | Sets `description: DISABLED` (cosmetic — no schema-level disable in OpenLDAP). |
| `.attribute(k, v)` | Custom attribute (multi-valued via repeated calls). |
| `.done()` | Writes the entry, returns the full DN. |

### Direct LDAP access

If you need raw LDAP ops the builder doesn't cover:

```java
@Test
void rawAccess(LdapFixture fix) throws Exception {
    try (LDAPConnection conn = fix.adminConnection()) {
        // Full UnboundID SDK access bound as cn=admin,dc=test,dc=local.
    }
}
```

`fix.classOuDn()` returns this class's OU DN — useful as the search base.

### Wiring the fixture into a service test

Most services take a `LdapConnectionFactory`; the simplest wiring is to point
a real `DirectoryConnection` at the testcontainer's mapped port:

```java
@OpenLdapTest
class MyServiceIntegrationTest {

    private MyService service;
    private DirectoryConnection conn;

    @BeforeEach
    void setUp(LdapFixture fix) {
        // Stub EncryptionService (the factory needs to decrypt the bind password,
        // and the test admin password is plaintext).
        EncryptionService enc = Mockito.mock(EncryptionService.class);
        Mockito.lenient().when(enc.decrypt(Mockito.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        LdapConnectionFactory factory = new LdapConnectionFactory(enc);
        service = new MyService(factory);

        OpenLdapContainer container = OpenLdapContainer.getInstance();
        conn = new DirectoryConnection();
        conn.setDirectoryType(DirectoryType.GENERIC);
        conn.setDisplayName("Test LDAP");
        conn.setHost(container.getHost());
        conn.setPort(container.getMappedLdapPort());
        conn.setBindDn(OpenLdapContainer.ADMIN_DN);
        conn.setBindPasswordEncrypted(OpenLdapContainer.ADMIN_PASSWORD);
        conn.setBaseDn(fix.classOuDn());
        // ... whatever else the connection needs
    }
}
```

See `ee/src/test/java/com/ldapportal/testsupport/ldap/LdapUserServiceIntegrationTest.java`
for a complete worked example.

### Image and lifecycle

- **Image:** `bitnamilegacy/openldap:2.6.10-debian-12-r4`. (The original
  `bitnami/openldap` namespace returned 404 after Broadcom's August-2025
  catalog reorganization. The legacy repo carries the same image content. If
  the Bitnami namespace is restored, switch back.)
- **Container start:** lazy on first `OpenLdapContainer.getInstance()` call.
- **Container stop:** JVM shutdown hook (no explicit teardown needed).
- **Per-class subtree:** created on `beforeAll`, dropped on `afterAll` via the
  SubtreeDelete control.
- **Baseline LDIF:** `ee/src/test/resources/ldap/baseline.ldif` —
  `dc=test,dc=local` suffix, `ou=test/groups/people/serviceAccounts`, plus 3
  seed users under `ou=seed,ou=test`.

### Self-tests for the fixture itself

`OpenLdapFixtureSelfTest` (5 tests) and `OpenLdapContainerBootSmokeTest` cover
the fixture API. If you change the fixture, run those first to confirm no
regression. Downstream test authors trust them.

## Running Playwright E2E tests

E2E tests drive the real Spring Boot app + Vue frontend + Postgres + OpenLDAP
via Testcontainers, with one command:

```bash
cd frontend
npm run e2e            # full suite
npm run e2e:smoke      # @smoke-tagged tests only (PR gate)
npm run e2e:ui         # interactive Playwright UI mode
npm run e2e:install    # download chromium binary (first-time setup)
```

### What's tested today

- `tests/e2e/spec/health.spec.ts @smoke` — app loads, /actuator/health UP, /api/v1/openapi reachable
- `tests/e2e/spec/login.spec.ts` — login form: happy path, bad password, logout

SP5 will backfill 13 more `@smoke` specs covering shipped feature areas.

### Architecture

- `playwright.config.ts` — config + webServer for backend (`mvn spring-boot:test-run`) + frontend (`npm run dev`). The Spring Boot command is platform-conditional (`mvnw.cmd` on Windows, `./mvnw` on Linux/macOS).
- `tests/e2e/global-setup.ts` — UI-logs-in superadmin and an `e2e-admin` ADMIN role once, saves cookies to `.auth/<role>.json`. The codebase has only SUPERADMIN and ADMIN roles (no READ_ONLY); read-only access is feature-permission-driven.
- `tests/e2e/fixtures.ts` — role-based `Page` fixtures (`superadmin`, `admin`) for instant pre-auth.
- `tests/e2e/helpers/` — `directory.ts:seededDirectoryId()` resolves the auto-seeded test DirectoryConnection's id by displayName; `user.ts` exports `userRow` + `waitForUserVisible`. SP5 will grow these.
- Backend test main: `ee/src/test/java/com/ldapportal/TestLDAPPortalApplication.java` — wraps real app with `TestcontainersConfiguration` (Postgres `@ServiceConnection` + reused SP3 OpenLdapContainer singleton + ApplicationRunner that auto-seeds a `DirectoryConnection` row pointing at the OpenLDAP container's dynamic mapped port).

### CI

- `.github/workflows/e2e-smoke.yml` — every PR (~3-5 min)
- `.github/workflows/e2e-full.yml` — nightly 3 AM UTC + workflow_dispatch + PR label `run-full-e2e`

### Convention: tag selectivity

Add `@smoke` in `test.describe()` titles for tests that should run on every PR:

```ts
test.describe('Search flow @smoke', () => { ... })
```

Every other test runs only nightly (or via dispatch / label). Keep `@smoke` to
~5-15 critical happy-path tests so PR cycle stays fast.
