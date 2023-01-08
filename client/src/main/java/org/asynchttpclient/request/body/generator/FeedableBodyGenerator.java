/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;

/**
 * {@link BodyGenerator} which may return just part of the payload at the time handler is requesting it.
 * If it happens, client becomes responsible for providing the rest of the chunks.
 */
public interface FeedableBodyGenerator extends BodyGenerator {

    boolean feed(ByteBuf buffer, boolean isLast) throws Exception;

    void setListener(FeedListener listener);
}
