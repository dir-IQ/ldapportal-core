// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Module-level config for the event backbone. Currently provides a system
 * UTC clock (injected into bridge + dispatcher for testability) and a
 * TransactionTemplate the dispatcher uses for short explicit transactions
 * around claim and resolve steps.
 */
@Configuration
public class EventBackboneConfig {

    @Bean
    public Clock eventBackboneClock() {
        return Clock.systemUTC();
    }

    @Bean
    public TransactionTemplate eventBackboneTxTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
