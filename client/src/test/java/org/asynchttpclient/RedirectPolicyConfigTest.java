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

import org.asynchttpclient.config.AsyncHttpClientConfigDefaults;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.asynchttpclient.Dsl.config;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration surface of {@link RedirectPolicy}. Modelled on {@link LoadBalanceConfigTest}, plus the two
 * guards that have no natural counterpart there: the {@code Builder(AsyncHttpClientConfig)} copy and the
 * agreement between the compiled-in posture and the shipped properties file.
 */
class RedirectPolicyConfigTest {

    private static final String PROPERTY =
            AsyncHttpClientConfigDefaults.ASYNC_CLIENT_CONFIG_ROOT
                    + AsyncHttpClientConfigDefaults.REDIRECT_POLICY_CONFIG;

    /**
     * Each constant declares its restrictions as explicit flags. Asserting the whole matrix is what makes a
     * constant added later declare its own, rather than inheriting whatever a {@code this != ALLOW_ALL} style
     * derivation would have produced.
     */
    @Test
    void flagsAreExplicitPerConstant() {
        assertFalse(RedirectPolicy.ALLOW_ALL.refusesInsecureDowngrade());
        assertFalse(RedirectPolicy.ALLOW_ALL.refusesCrossOriginBodyReplay());

        assertTrue(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE.refusesInsecureDowngrade());
        assertFalse(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE.refusesCrossOriginBodyReplay());

        assertTrue(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY.refusesInsecureDowngrade());
        assertTrue(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY.refusesCrossOriginBodyReplay());
    }

    @Test
    void defaultIsTheShippedValue() {
        assertEquals(RedirectPolicy.ALLOW_ALL, config().build().getRedirectPolicy());
    }

    @Test
    void builderSetsEachConstant() {
        for (RedirectPolicy policy : RedirectPolicy.values()) {
            assertEquals(policy, config().setRedirectPolicy(policy).build().getRedirectPolicy(), policy.name());
        }
    }

    @Test
    void nullSetterResetsToTheConfiguredDefault() {
        AsyncHttpClientConfig config = config()
                .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY)
                .setRedirectPolicy(null)
                .build();
        assertEquals(AsyncHttpClientConfigDefaults.defaultRedirectPolicy(), config.getRedirectPolicy());
    }

    /**
     * The only guard on the {@code Builder(AsyncHttpClientConfig)} copy: omitting a field there is silent -
     * no compiler error, no Revapi signal - and the setting reverts to its properties default.
     */
    @Test
    void copyConstructorPreservesValue() {
        AsyncHttpClientConfig source = config()
                .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY)
                .build();
        AsyncHttpClientConfig copy = new DefaultAsyncHttpClientConfig.Builder(source).build();
        assertEquals(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY, copy.getRedirectPolicy());
    }

    /**
     * Pins the property that keeps a third-party {@link AsyncHttpClientConfig} working: the accessor must have
     * a default body, or every implementation outside this repository fails to compile.
     */
    @Test
    void configAccessorIsDefaultBodied() throws NoSuchMethodException {
        assertTrue(AsyncHttpClientConfig.class.getMethod("getRedirectPolicy").isDefault());
    }

    /**
     * The interface default body resolves through {@code defaultRedirectPolicy()} rather than returning a
     * literal, so a third-party config honours the property too.
     */
    @Test
    void interfaceDefaultBodyResolvesThroughTheDefaults() {
        // CALLS_REAL_METHODS runs the interface's own default body, which is what a third-party
        // AsyncHttpClientConfig that does not override the accessor gets.
        AsyncHttpClientConfig bare = mock(AsyncHttpClientConfig.class, CALLS_REAL_METHODS);
        assertEquals(AsyncHttpClientConfigDefaults.defaultRedirectPolicy(), bare.getRedirectPolicy());
    }

    /**
     * And it really does resolve, rather than return a baked-in constant: a property set at runtime reaches an
     * implementation that never overrides the accessor.
     */
    @Test
    void interfaceDefaultBodyHonoursTheProperty() {
        System.setProperty(PROPERTY, "REFUSE_INSECURE_DOWNGRADE");
        try {
            AsyncHttpClientConfigHelper.reloadProperties();
            AsyncHttpClientConfig bare = mock(AsyncHttpClientConfig.class, CALLS_REAL_METHODS);
            assertEquals(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE, bare.getRedirectPolicy());
        } finally {
            System.clearProperty(PROPERTY);
            AsyncHttpClientConfigHelper.reloadProperties();
        }
    }

    /**
     * The compiled-in posture and {@code ahc-default.properties} must agree, so a later change to one cannot
     * leave the other behind. The property is forced to an invalid value first: otherwise the resolver simply
     * reads the properties file back and the assertion compares it with itself.
     */
    @Test
    void shippedPropertyMatchesTheCompiledDefault() throws IOException {
        Properties shipped = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/org/asynchttpclient/config/ahc-default.properties")) {
            assertNotNull(in, "ahc-default.properties must be on the test classpath");
            shipped.load(in);
        }
        String shippedValue = shipped.getProperty(PROPERTY);
        assertNotNull(shippedValue, PROPERTY + " must be present in ahc-default.properties");

        System.setProperty(PROPERTY, "not-a-constant-name");
        try {
            AsyncHttpClientConfigHelper.reloadProperties();
            // With the property unparseable the resolver returns the compiled-in constant, so this really does
            // compare the constant against the shipped file rather than the file against itself.
            assertEquals(RedirectPolicy.valueOf(shippedValue.trim()),
                    AsyncHttpClientConfigDefaults.defaultRedirectPolicy(),
                    "ahc-default.properties and the compiled-in default must agree");
        } finally {
            System.clearProperty(PROPERTY);
            AsyncHttpClientConfigHelper.reloadProperties();
        }
    }

    @Test
    void invalidPropertyFallsBackInsteadOfFailingClientConstruction() {
        System.setProperty(PROPERTY, "REFUSE_DOWNGRADE");
        try {
            AsyncHttpClientConfigHelper.reloadProperties();
            // Must not throw: this runs from a Builder instance field initialiser, so a throw would leave a
            // typo'd property with no code-level escape - not even setRedirectPolicy(...) could recover.
            assertEquals(RedirectPolicy.ALLOW_ALL, config().build().getRedirectPolicy());
            assertEquals(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE,
                    config().setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE).build().getRedirectPolicy());
        } finally {
            System.clearProperty(PROPERTY);
            AsyncHttpClientConfigHelper.reloadProperties();
        }
    }

    @Test
    void propertyIsReadCaseInsensitivelyAndAcceptsHyphens() {
        System.setProperty(PROPERTY, " refuse-insecure-downgrade ");
        try {
            AsyncHttpClientConfigHelper.reloadProperties();
            assertEquals(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE,
                    AsyncHttpClientConfigDefaults.defaultRedirectPolicy());
        } finally {
            System.clearProperty(PROPERTY);
            AsyncHttpClientConfigHelper.reloadProperties();
        }
    }
}
