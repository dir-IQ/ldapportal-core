// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.directory.event;

import java.util.UUID;

/**
 * Published by {@code DirectoryConnectionService} at the end of both
 * {@code createDirectory} and {@code updateDirectory}, inside the
 * persisting transaction. The capability refresher listens on this
 * with {@code @TransactionalEventListener(AFTER_COMMIT)} so the
 * LDAP root-DSE probe happens AFTER the row has committed — taking
 * the network I/O off the save path and ensuring that a probe-time
 * pool creation can never leak under a UUID that ends up rolled back.
 *
 * <p>Distinct from {@link DirectoryCreatedEvent} on purpose: the
 * existing event has community-edition listeners (alerting auto-seed)
 * that rely on its synchronous-in-transaction semantics — failure
 * there rolls the create back, which is the documented behaviour. The
 * saved-event uses different phase semantics (AFTER_COMMIT, fires for
 * updates too), so it gets its own type rather than overloading the
 * created-event meaning.
 */
public record DirectoryConnectionSavedEvent(UUID directoryId) {}
