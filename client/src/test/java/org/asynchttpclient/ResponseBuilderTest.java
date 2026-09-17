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
package org.asynchttpclient;

import io.netty.buffer.Unpooled;
import org.asynchttpclient.netty.EagerResponseBodyPart;
import org.asynchttpclient.netty.NettyResponseStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResponseBuilderTest {

    private static void accumulateBody(Response.ResponseBuilder builder, String body) {
        builder.accumulate(new NettyResponseStatus(null, null, null));
        builder.accumulate(new EagerResponseBodyPart(Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8)), true));
    }

    @Test
    public void testAResponseKeepsItsBodyWhenTheBuilderIsReused() {
        // AsyncCompletionHandler resets its builder on every onStatusReceived, so a handler instance reused for
        // a second request refills the same list. A response holding that list would take on the second body.
        Response.ResponseBuilder builder = new Response.ResponseBuilder();
        accumulateBody(builder, "first");
        Response first = builder.build();

        builder.reset();
        accumulateBody(builder, "second");
        Response second = builder.build();

        assertEquals("first", first.getResponseBody(StandardCharsets.UTF_8));
        assertEquals("second", second.getResponseBody(StandardCharsets.UTF_8));
    }

    @Test
    public void testAResponseKeepsItsBodyWhenTheBuilderAccumulatesAgain() {
        // Without a reset in between, a further part must not appear in a response that was already built.
        Response.ResponseBuilder builder = new Response.ResponseBuilder();
        accumulateBody(builder, "first");
        Response first = builder.build();

        builder.accumulate(new EagerResponseBodyPart(Unpooled.wrappedBuffer(" second".getBytes(StandardCharsets.UTF_8)), true));

        assertEquals("first", first.getResponseBody(StandardCharsets.UTF_8));
        assertEquals("first second", builder.build().getResponseBody(StandardCharsets.UTF_8));
    }

    @Test
    public void testAResponseKeepsItsBodyWhenTheBuilderIsResetAndNotRebuilt() {
        Response.ResponseBuilder builder = new Response.ResponseBuilder();
        accumulateBody(builder, "first");
        Response first = builder.build();

        builder.reset();

        assertTrue(first.hasResponseBody());
        assertEquals("first", first.getResponseBody(StandardCharsets.UTF_8));
    }
}
