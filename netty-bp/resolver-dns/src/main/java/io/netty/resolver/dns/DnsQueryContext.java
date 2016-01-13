/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.resolver.dns;

import io.netty.buffer.Unpooled;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.OneTimeTask;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

final class DnsQueryContext {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DnsQueryContext.class);

    private final DnsNameResolver parent;
    private final Promise<AddressedEnvelope<DnsResponse, InetSocketAddress>> promise;
    private final int id;
    private final DnsQuestion question;
    private final DnsRecord optResource;
    private final InetSocketAddress nameServerAddr;

    private final boolean recursionDesired;
    private volatile ScheduledFuture<?> timeoutFuture;

    DnsQueryContext(DnsNameResolver parent,
                    InetSocketAddress nameServerAddr,
                    DnsQuestion question, Promise<AddressedEnvelope<DnsResponse, InetSocketAddress>> promise) {

        this.parent = parent;
        this.nameServerAddr = nameServerAddr;
        this.question = question;
        this.promise = promise;
        recursionDesired = parent.isRecursionDesired();
        id = parent.queryContextManager.add(this);

        if (parent.isOptResourceEnabled()) {
            optResource = new DefaultDnsRawRecord(
                    StringUtil.EMPTY_STRING, DnsRecordType.OPT, parent.maxPayloadSize(), 0, Unpooled.EMPTY_BUFFER);
        } else {
            optResource = null;
        }
    }

    InetSocketAddress nameServerAddr() {
        return nameServerAddr;
    }

    DnsQuestion question() {
        return question;
    }

    void query() {
        final DnsQuestion question = question();
        final InetSocketAddress nameServerAddr = nameServerAddr();
        final DatagramDnsQuery query = new DatagramDnsQuery(null, nameServerAddr, id);
        query.setRecursionDesired(recursionDesired);
        query.setRecord(DnsSection.QUESTION, question);
        if (optResource != null) {
            query.setRecord(DnsSection.ADDITIONAL, optResource);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{} WRITE: [{}: {}], {}", parent.ch, id, nameServerAddr, question);
        }

        sendQuery(query);
    }

    private void sendQuery(final DnsQuery query) {
        if (parent.bindFuture.isDone()) {
            writeQuery(query);
        } else {
            parent.bindFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        writeQuery(query);
                    } else {
                        promise.tryFailure(future.cause());
                    }
                }
            });
        }
    }

    private void writeQuery(final DnsQuery query) {
        final ChannelFuture writeFuture = parent.ch.writeAndFlush(query);
        if (writeFuture.isDone()) {
            onQueryWriteCompletion(writeFuture);
        } else {
            writeFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    onQueryWriteCompletion(writeFuture);
                }
            });
        }
    }

    private void onQueryWriteCompletion(ChannelFuture writeFuture) {
        if (!writeFuture.isSuccess()) {
            setFailure("failed to send a query", writeFuture.cause());
            return;
        }

        // Schedule a query timeout task if necessary.
        final long queryTimeoutMillis = parent.queryTimeoutMillis();
        if (queryTimeoutMillis > 0) {
            timeoutFuture = parent.ch.eventLoop().schedule(new OneTimeTask() {
                @Override
                public void run() {
                    if (promise.isDone()) {
                        // Received a response before the query times out.
                        return;
                    }

                    setFailure("query timed out after " + queryTimeoutMillis + " milliseconds", null);
                }
            }, queryTimeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    void finish(AddressedEnvelope<? extends DnsResponse, InetSocketAddress> envelope) {
        final DnsResponse res = envelope.content();
        if (res.count(DnsSection.QUESTION) != 1) {
            logger.warn("Received a DNS response with invalid number of questions: {}", envelope);
            return;
        }

        if (!question().equals(res.recordAt(DnsSection.QUESTION))) {
            logger.warn("Received a mismatching DNS response: {}", envelope);
            return;
        }

        setSuccess(envelope);
    }

    private void setSuccess(AddressedEnvelope<? extends DnsResponse, InetSocketAddress> envelope) {
        parent.queryContextManager.remove(nameServerAddr(), id);

        // Cancel the timeout task.
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }

        Promise<AddressedEnvelope<DnsResponse, InetSocketAddress>> promise = this.promise;
        if (promise.setUncancellable()) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<DnsResponse, InetSocketAddress> castResponse =
                    (AddressedEnvelope<DnsResponse, InetSocketAddress>) envelope.retain();
            promise.setSuccess(castResponse);
        }
    }

    private void setFailure(String message, Throwable cause) {
        final InetSocketAddress nameServerAddr = nameServerAddr();
        parent.queryContextManager.remove(nameServerAddr, id);

        final StringBuilder buf = new StringBuilder(message.length() + 64);
        buf.append('[')
           .append(nameServerAddr)
           .append("] ")
           .append(message)
           .append(" (no stack trace available)");

        final DnsNameResolverException e;
        if (cause != null) {
            e = new DnsNameResolverException(nameServerAddr, question(), buf.toString(), cause);
        } else {
            e = new DnsNameResolverException(nameServerAddr, question(), buf.toString());
        }

        promise.tryFailure(e);
    }
}
