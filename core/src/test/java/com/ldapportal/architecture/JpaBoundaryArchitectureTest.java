// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the snapshot/persistence boundary for the two async-dispatch
 * subsystems: their JPA entities must not be touched outside the narrow
 * set of classes that own the read/write transaction. Non-transactional
 * dispatch code consumes immutable snapshots instead, so a stray LAZY
 * association can't trip {@code LazyInitializationException} inside a
 * generic transient-failure catch and retry-loop forever.
 *
 * <p><b>Note on rule shape (deviation from the migration plan's sketch).</b>
 * The plan assumed {@code ..ldap.replication.snapshot..} /
 * {@code ..ldap.replication.persistence..} subpackages and used a package
 * allowlist. The replication package is in fact <em>flat</em> — the
 * snapshot factories ({@code ReplicationLinkSnapshot},
 * {@code ReplicationEventSnapshot}) and the persister
 * ({@code ReplicationEventPersister}) sit alongside the dispatch classes —
 * so a package allowlist would be vacuous. The replication rule therefore
 * uses a class-name allowlist within the flat package. The events
 * subsystem, by contrast, keeps its entities in their own
 * {@code ..core.events.entity} package and they genuinely never escape the
 * module, so its rule is expressed as module containment. Both encode the
 * same R0 invariant ("snapshot pattern at the JPA boundary") and both pass
 * against the {@code feat/directory-sync} baseline.
 */
@AnalyzeClasses(packages = "com.ldapportal", importOptions = ImportOption.DoNotIncludeTests.class)
class JpaBoundaryArchitectureTest {

    private static final String REPLICATION_LINK  = "com.ldapportal.entity.ReplicationLink";
    private static final String REPLICATION_EVENT = "com.ldapportal.entity.ReplicationEvent";
    private static final String OUTBOX_ENTRY      = "com.ldapportal.core.events.entity.OutboxEntry";
    private static final String EVENT_SUBSCRIPTION = "com.ldapportal.core.events.entity.EventSubscription";

    /**
     * Within the async replication dispatch package, only the snapshot
     * factories ({@code *Snapshot}) and the persister ({@code *Persister})
     * may depend on the {@link com.ldapportal.entity.ReplicationLink} /
     * {@link com.ldapportal.entity.ReplicationEvent} JPA entities. Every
     * other class (worker, delivery, enqueuer, codec, mappers, the wrapper)
     * must go through a {@code ReplicationLinkSnapshot} /
     * {@code ReplicationEventSnapshot} obtained from {@code ReplicationReadOps}
     * inside a {@code @Transactional} method.
     */
    @ArchTest
    static final ArchRule replication_entities_stay_behind_snapshot_boundary =
            noClasses()
                    .that().resideInAPackage("com.ldapportal.ldap.replication..")
                    .and().haveSimpleNameNotEndingWith("Snapshot")
                    .and().haveSimpleNameNotEndingWith("Persister")
                    .should().dependOnClassesThat().haveFullyQualifiedName(REPLICATION_LINK)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(REPLICATION_EVENT)
                    .because("JPA entities must not cross out of the snapshot/persistence "
                           + "boundary. Use a ReplicationLinkSnapshot / ReplicationEventSnapshot "
                           + "obtained from ReplicationReadOps inside a @Transactional method.");

    /**
     * The outbox entities ({@link com.ldapportal.core.events.entity.OutboxEntry},
     * {@link com.ldapportal.core.events.entity.EventSubscription}) must stay
     * within the {@code com.ldapportal.core.events} module. Anything outside —
     * other services, controllers, addons — consumes the corresponding
     * snapshots via {@code OutboundEventReadOps}, never the entities.
     */
    @ArchTest
    static final ArchRule outbox_entities_stay_within_events_module =
            noClasses()
                    .that().resideOutsideOfPackage("com.ldapportal.core.events..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(OUTBOX_ENTRY)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(EVENT_SUBSCRIPTION)
                    .because("Outbox JPA entities must not leak out of the events module. "
                           + "Use an OutboxEntrySnapshot / EventSubscriptionSnapshot obtained "
                           + "from OutboundEventReadOps instead.");
}
