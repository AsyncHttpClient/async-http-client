package org.asynchttpclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAsyncHttpClientConfigTest {

    @Test
    void testHttp2MaxDecompressedResponseSize_DefaultIs256MiB() {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
        assertEquals(256L * 1024 * 1024, config.getHttp2MaxDecompressedResponseSize(),
                "Default HTTP/2 max decompressed response size should be 256 MiB");
    }

    @Test
    void testHttp2MaxDecompressedResponseSize_SetCustom() {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setHttp2MaxDecompressedResponseSize(123_456L)
                .build();
        assertEquals(123_456L, config.getHttp2MaxDecompressedResponseSize(),
                "Builder must plumb the configured value through (the knob was previously unconfigurable)");
    }

    @Test
    void testHttp2MaxDecompressedResponseSize_ZeroDisables() {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setHttp2MaxDecompressedResponseSize(0L)
                .build();
        assertEquals(0L, config.getHttp2MaxDecompressedResponseSize(), "0 must disable the limit");
    }

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
