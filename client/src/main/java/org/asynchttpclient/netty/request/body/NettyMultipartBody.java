/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request.body;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.request.body.multipart.MultipartBody;
import org.asynchttpclient.request.body.multipart.Part;

import java.util.List;

import static org.asynchttpclient.request.body.multipart.MultipartUtils.newMultipartBody;

public class NettyMultipartBody extends NettyBodyBody {

    private final String contentTypeOverride;

    public NettyMultipartBody(List<Part> parts, HttpHeaders headers, AsyncHttpClientConfig config) {
        this(newMultipartBody(parts, headers), config);
    }

    private NettyMultipartBody(MultipartBody body, AsyncHttpClientConfig config) {
        super(body, config);
        contentTypeOverride = body.getContentType();
    }

    @Override
    public String getContentTypeOverride() {
        return contentTypeOverride;
    }
}
