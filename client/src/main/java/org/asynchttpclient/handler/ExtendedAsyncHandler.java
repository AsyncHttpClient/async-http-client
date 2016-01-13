/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.handler;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.List;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.netty.request.NettyRequest;

public abstract class ExtendedAsyncHandler<T> implements AsyncHandler<T>, AsyncHandlerExtensions {

    @Override
    public void onHostnameResolutionAttempt(String name) {
    }

    @Override
    public void onHostnameResolutionSuccess(String name, List<InetSocketAddress> addresses) {
    }

    @Override
    public void onHostnameResolutionFailure(String name, Throwable cause) {
    }

    @Override
    public void onTcpConnectAttempt(InetSocketAddress address) {
    }

    @Override
    public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
    }

    @Override
    public void onTcpConnectFailure(InetSocketAddress remoteAddress, Throwable cause) {
    }

    @Override
    public void onTlsHandshakeAttempt() {
    }

    @Override
    public void onTlsHandshakeSuccess() {
    }

    @Override
    public void onTlsHandshakeFailure(Throwable cause) {
    }

    @Override
    public void onConnectionPoolAttempt() {
    }

    @Override
    public void onConnectionPooled(Channel connection) {
    }

    @Override
    public void onConnectionOffer(Channel connection) {
    }

    @Override
    public void onRequestSend(NettyRequest request) {
    }

    @Override
    public void onRetry() {
    }
}
