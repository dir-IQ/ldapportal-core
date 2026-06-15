// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the snapshot/persistence boundary for the async event-outbox
 * subsystem: its JPA entities must not be touched outside the narrow set of
 * classes that own the read/write transaction. Non-transactional dispatch
 * code consumes immutable snapshots instead, so a stray LAZY association
 * can't trip {@code LazyInitializationException} inside a generic
 * transient-failure catch and retry-loop forever.
 *
 * <p>The legacy replication subsystem carried an analogous rule; it was
 * removed with that subsystem when the sync engine was rebaselined. The
 * membership engine reintroduces its own boundary rule when it grows
 * non-transactional dispatch.
 */
@AnalyzeClasses(packages = "com.ldapportal", importOptions = ImportOption.DoNotIncludeTests.class)
class JpaBoundaryArchitectureTest {

    private static final String OUTBOX_ENTRY      = "com.ldapportal.core.events.entity.OutboxEntry";
    private static final String EVENT_SUBSCRIPTION = "com.ldapportal.core.events.entity.EventSubscription";

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
