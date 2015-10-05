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
package org.asynchttpclient.netty.request.body;

import static org.asynchttpclient.request.body.multipart.MultipartUtils.newMultipartBody;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.List;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.request.body.multipart.MultipartBody;
import org.asynchttpclient.request.body.multipart.Part;

public class NettyMultipartBody extends NettyBodyBody {

    private final String contentType;

    public NettyMultipartBody(List<Part> parts, HttpHeaders headers, AsyncHttpClientConfig config) {
        this(newMultipartBody(parts, headers), config);
    }

    private NettyMultipartBody(MultipartBody body, AsyncHttpClientConfig config) {
        super(body, config);
        contentType = body.getContentType();
    }

    @Override
    public String getContentType() {
        return contentType;
    }
}
