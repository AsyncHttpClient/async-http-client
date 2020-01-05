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

import io.netty.channel.unix.DomainSocketAddress;
import io.netty.resolver.SimpleNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.SocketUtils;

import java.util.Arrays;
import java.util.List;

public class DefaultDomainNameResolver extends SimpleNameResolver<DomainSocketAddress> {
    public DefaultDomainNameResolver(EventExecutor executor) {
        super(executor);
    }

    @Override
    protected void doResolve(String path, Promise<DomainSocketAddress> promise) throws Exception {
        promise.setSuccess(new DomainSocketAddress(path));
    }

    @Override
    protected void doResolveAll(String path, Promise<List<DomainSocketAddress>> promise) throws Exception {
        promise.setSuccess(Arrays.asList(new DomainSocketAddress(path)));
    }
}
