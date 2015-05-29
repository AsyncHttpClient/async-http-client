/*******************************************************************************
 * Copyright (c) 2010-2012 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * The Apache License v2.0 is available at
 *   http://www.apache.org/licenses/LICENSE-2.0.html
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.asynchttpclient.netty.channel;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.channel.MaxConnectionsInThreads;
import org.asynchttpclient.config.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyProviderUtil;

public class NettyMaxConnectionsInThreads extends MaxConnectionsInThreads {
    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }
}
