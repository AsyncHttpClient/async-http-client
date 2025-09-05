package org.asynchttpclient.netty;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.uring.IoUring;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class NettyTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    public void epollIsAvailableOnLinux() {
        assertTrue(Epoll.isAvailable());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void ioUringIsAvailableOnLinux() {
        assertTrue(IoUring.isAvailable());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    public void kqueueIsAvailableOnMac() {
        assertTrue(KQueue.isAvailable());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void brotliIsAvailableOnLinux() {
        assertTrue(Brotli.isAvailable());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    public void brotliIsAvailableOnMac() {
        assertTrue(Brotli.isAvailable());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void zstdIsAvailableOnLinux() {
        assertTrue(Zstd.isAvailable());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    public void zstdIsAvailableOnMac() {
        assertTrue(Zstd.isAvailable());
    }
}
