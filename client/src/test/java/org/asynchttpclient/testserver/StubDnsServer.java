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
package org.asynchttpclient.testserver;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;

import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * In-process DNS server. Answers every A query with 127.0.0.1; {@link #UNKNOWN_HOST} gets NXDOMAIN.
 */
public final class StubDnsServer implements Closeable {

    public static final String UNKNOWN_HOST = "nonexistent.invalid";

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final Channel channel;

    public StubDnsServer() throws InterruptedException {
        channel = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ch.pipeline().addLast(new DatagramDnsQueryDecoder(), new DatagramDnsResponseEncoder(), new QueryHandler());
                    }
                })
                .bind(InetAddress.getLoopbackAddress(), 0)
                .sync()
                .channel();
    }

    public InetSocketAddress getAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, SECONDS).syncUninterruptibly();
    }

    private static final class QueryHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery query) {
            DnsQuestion question = query.recordAt(DnsSection.QUESTION);
            DatagramDnsResponse response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
            response.addRecord(DnsSection.QUESTION, question);
            // Decoded names end with a dot and may carry a search domain.
            if (question.name().startsWith(UNKNOWN_HOST)) {
                response.setCode(DnsResponseCode.NXDOMAIN);
            } else if (question.type() == DnsRecordType.A) {
                response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(question.name(), DnsRecordType.A, 60,
                        Unpooled.wrappedBuffer(new byte[]{127, 0, 0, 1})));
            }
            ctx.writeAndFlush(response);
        }
    }
}
