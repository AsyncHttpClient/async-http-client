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
package org.asynchttpclient.resolver;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * A blocking {@link NameResolver} that uses Java <code>InetAddress.getAllByName</code>.
 */
public enum JdkNameResolver implements NameResolver {

    INSTANCE;

    @Override
    public Future<List<InetSocketAddress>> resolve(String name, int port) {

        Promise<List<InetSocketAddress>> promise = ImmediateEventExecutor.INSTANCE.newPromise();
        try {
            InetAddress[] resolved = InetAddress.getAllByName(name);
            List<InetSocketAddress> socketResolved = new ArrayList<InetSocketAddress>(resolved.length);
            for (InetAddress res : resolved) {
                socketResolved.add(new InetSocketAddress(res, port));
            }
            return promise.setSuccess(socketResolved);
        } catch (UnknownHostException e) {
            return promise.setFailure(e);
        }
    }
}
