/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpMessage;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class EventPipelineTest extends AbstractBasicTest {

  @Test
  public void asyncPipelineTest() throws Exception {

    Consumer<Channel> httpAdditionalPipelineInitializer = channel -> channel.pipeline().addBefore("inflater",
            "copyEncodingHeader", new CopyEncodingHandler());

    try (AsyncHttpClient p = asyncHttpClient(
            config().setHttpAdditionalChannelInitializer(httpAdditionalPipelineInitializer))) {
      final CountDownLatch l = new CountDownLatch(1);
      p.executeRequest(get(getTargetUrl()), new AsyncCompletionHandlerAdapter() {
        @Override
        public Response onCompleted(Response response) {
          try {
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("X-Original-Content-Encoding"), "<original encoding>");
          } finally {
            l.countDown();
          }
          return response;
        }
      }).get();
      if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
        fail("Timeout out");
      }
    }
  }

  private static class CopyEncodingHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object e) {
      if (e instanceof HttpMessage) {
        HttpMessage m = (HttpMessage) e;
        // for test there is no Content-Encoding header so just hard
        // coding value
        // for verification
        m.headers().set("X-Original-Content-Encoding", "<original encoding>");
      }
      ctx.fireChannelRead(e);
    }
  }
}
