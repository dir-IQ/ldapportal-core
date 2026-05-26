// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class EntraConfig {

    @Bean
    public HttpClient entraHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
