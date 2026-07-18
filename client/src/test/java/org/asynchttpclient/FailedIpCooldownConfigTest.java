/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedIpCooldownConfigTest {

    @Test
    void defaultsToEnabledWithTenSecondPeriod() {
        AsyncHttpClientConfig config = config().build();
        assertTrue(config.isFailedIpCooldownEnabled());
        assertEquals(Duration.ofSeconds(10), config.getFailedIpCooldownPeriod());
    }

    @Test
    void builderSetsEnabled() {
        assertFalse(config().setFailedIpCooldownEnabled(false).build().isFailedIpCooldownEnabled());
    }

    @Test
    void builderSetsPeriod() {
        AsyncHttpClientConfig config = config().setFailedIpCooldownPeriod(Duration.ofSeconds(30)).build();
        assertEquals(Duration.ofSeconds(30), config.getFailedIpCooldownPeriod());
    }

    @Test
    void nullPeriodResetsToDefault() {
        AsyncHttpClientConfig config = config().setFailedIpCooldownPeriod(null).build();
        assertEquals(Duration.ofSeconds(10), config.getFailedIpCooldownPeriod());
    }

    @Test
    void negativePeriodIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config().setFailedIpCooldownPeriod(Duration.ofSeconds(-1)));
    }

    @Test
    void zeroPeriodIsAccepted() {
        // zero is a valid (if degenerate) period — turning the cooldown off is done via setFailedIpCooldownEnabled(false)
        AsyncHttpClientConfig config = config().setFailedIpCooldownPeriod(Duration.ZERO).build();
        assertEquals(Duration.ZERO, config.getFailedIpCooldownPeriod());
    }

    @Test
    void copyConstructorPreservesValues() {
        AsyncHttpClientConfig source = config()
                .setFailedIpCooldownEnabled(false)
                .setFailedIpCooldownPeriod(Duration.ofSeconds(42))
                .build();
        AsyncHttpClientConfig copy = new DefaultAsyncHttpClientConfig.Builder(source).build();
        assertFalse(copy.isFailedIpCooldownEnabled());
        assertEquals(Duration.ofSeconds(42), copy.getFailedIpCooldownPeriod());
    }
}
