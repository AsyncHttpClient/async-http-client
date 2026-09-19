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
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.Properties;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration surface of {@link RedirectPolicy}, on the {@link LoadBalanceConfigTest} model, plus two guards
 * it has no counterpart for: the {@code Builder(AsyncHttpClientConfig)} copy, and the compiled-in default
 * agreeing with the shipped properties file.
 */
class RedirectPolicyConfigTest {

    private static final String PROPERTY =
            AsyncHttpClientConfigDefaults.ASYNC_CLIENT_CONFIG_ROOT
                    + AsyncHttpClientConfigDefaults.REDIRECT_POLICY_CONFIG;

    /**
     * An implementation that overrides nothing, so calls land on the interface's own default bodies. Mockito's
     * CALLS_REAL_METHODS cannot do this - it reports "Cannot call abstract real method" for a default method
     * on several JDK and platform combinations.
     */
    private static AsyncHttpClientConfig overridingNothing() {
        return (AsyncHttpClientConfig) Proxy.newProxyInstance(
                AsyncHttpClientConfig.class.getClassLoader(),
                new Class<?>[]{AsyncHttpClientConfig.class},
                (proxy, method, args) -> {
                    if (!method.isDefault()) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return MethodHandles.privateLookupIn(AsyncHttpClientConfig.class, MethodHandles.lookup())
                            .unreflectSpecial(method, AsyncHttpClientConfig.class)
                            .bindTo(proxy)
                            .invokeWithArguments(args == null ? new Object[0] : args);
                });
    }

    /** The whole matrix, so a constant added later has to declare its own flags rather than inherit them. */
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

    /** The only guard on the copy constructor: omitting a field there is silent and reverts to the default. */
    @Test
    void copyConstructorPreservesValue() {
        AsyncHttpClientConfig source = config()
                .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY)
                .build();
        AsyncHttpClientConfig copy = new DefaultAsyncHttpClientConfig.Builder(source).build();
        assertEquals(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY, copy.getRedirectPolicy());
    }

    /** Without a default body every third-party AsyncHttpClientConfig stops compiling. */
    @Test
    void configAccessorIsDefaultBodied() throws NoSuchMethodException {
        assertTrue(AsyncHttpClientConfig.class.getMethod("getRedirectPolicy").isDefault());
    }

    /** The default body resolves rather than returning a literal, so a third-party config honours the property. */
    @Test
    void interfaceDefaultBodyResolvesThroughTheDefaults() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultRedirectPolicy(),
                overridingNothing().getRedirectPolicy());
    }

    /** And really resolves: a runtime property reaches an implementation that never overrides the accessor. */
    @Test
    void interfaceDefaultBodyHonoursTheProperty() {
        System.setProperty(PROPERTY, "REFUSE_INSECURE_DOWNGRADE");
        try {
            AsyncHttpClientConfigHelper.reloadProperties();
            assertEquals(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE, overridingNothing().getRedirectPolicy());
        } finally {
            System.clearProperty(PROPERTY);
            AsyncHttpClientConfigHelper.reloadProperties();
        }
    }

    /**
     * The compiled-in posture and {@code ahc-default.properties} must agree. The property is forced invalid
     * first, or the resolver just reads the file back and the assertion compares it with itself.
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
            // Unparseable, so the resolver returns the compiled-in constant - which is what we want to compare.
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
            // Must not throw: from a Builder field initialiser, a throw leaves no way to recover in code.
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
