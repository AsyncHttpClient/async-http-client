/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package com.ning.http.client.providers.netty.request.body;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.multipart.MultipartBody;
import com.ning.http.client.multipart.MultipartUtils;
import com.ning.http.client.multipart.Part;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;

import java.util.List;

public class NettyMultipartBody extends NettyBodyBody {

    private final String contentType;

    public NettyMultipartBody(List<Part> parts, FluentCaseInsensitiveStringsMap headers, NettyAsyncHttpProviderConfig nettyConfig) {
        this(MultipartUtils.newMultipartBody(parts, headers), nettyConfig);
    }

    private NettyMultipartBody(MultipartBody body, NettyAsyncHttpProviderConfig nettyConfig) {
        super(body, nettyConfig);
        contentType = body.getContentType();
    }

    @Override
    public String getContentType() {
        return contentType;
    }
}
