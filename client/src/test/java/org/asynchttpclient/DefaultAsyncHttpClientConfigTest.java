package org.asynchttpclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAsyncHttpClientConfigTest {
    @Test
    void testStripAuthorizationOnRedirect_DefaultIsFalse() {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
        assertFalse(config.isStripAuthorizationOnRedirect(), "Default should be false");
    }

    @Test
    void testStripAuthorizationOnRedirect_SetTrue() {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setStripAuthorizationOnRedirect(true)
                .build();
        assertTrue(config.isStripAuthorizationOnRedirect(), "Should be true when set");
    }

    @Test
    void testStripAuthorizationOnRedirect_SetFalse() {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setStripAuthorizationOnRedirect(false)
                .build();
        assertFalse(config.isStripAuthorizationOnRedirect(), "Should be false when set to false");
    }
}
