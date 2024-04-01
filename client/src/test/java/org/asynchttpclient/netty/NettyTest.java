package org.asynchttpclient.netty;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import io.netty.incubator.channel.uring.IOUring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class NettyTest {
    @Test
    @EnabledOnOs(value = OS.LINUX)
    public void epollIsAvailableOnLinux() {
        assertTrue(Epoll.isAvailable());
    }

    @Test
    @EnabledOnOs(value = OS.LINUX)
    public void ioUringIsAvailableOnLinux() {
        assertTrue(IOUring.isAvailable());
    }

    @Test
    @EnabledOnOs(value = OS.MAC)
    public void kqueueIsAvailableOnMac() {
        assertTrue(KQueue.isAvailable());
    }

    @Test
    @EnabledOnOs(value = OS.LINUX)
    public void brotliIsAvailableOnLinux() {
        assertTrue(Brotli.isAvailable());
    }

    @Test
    @EnabledOnOs(value = OS.MAC)
    public void brotliIsAvailableOnMac() {
        assertTrue(Brotli.isAvailable());
    }

    @Test
    @EnabledOnOs(value = OS.LINUX)
    public void zstdIsAvailableOnLinux() {
        assertTrue(Zstd.isAvailable());
    }

    @Test
    @EnabledOnOs(value = OS.MAC)
    public void zstdIsAvailableOnMac() {
        assertTrue(Zstd.isAvailable());
    }
}
