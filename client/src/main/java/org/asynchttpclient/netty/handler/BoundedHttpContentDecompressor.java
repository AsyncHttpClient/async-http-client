/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.util.ReferenceCountUtil;

/**
 * An {@link HttpContentDecompressor} that bounds how far a response body may inflate, guarding against a
 * decompression bomb: a small, highly compressible response that expands without limit once decoded and
 * exhausts the client's heap.
 *
 * <p>The bound is <strong>cumulative over one response body</strong>, and that is the whole point. Netty's
 * own {@code maxAllocation} constructor parameter is not a substitute: it reaches {@code ZlibDecoder} as
 * the maxCapacity of the buffer produced by <em>one</em> {@code decode()} call, with no counter spanning
 * calls. Since the HTTP codec hands the decompressor at most
 * {@link org.asynchttpclient.AsyncHttpClientConfig#getHttpClientCodecMaxChunkSize()} bytes at a time
 * (8 KiB by default), a body at any ordinary compression ratio never reaches it however large the total
 * becomes. Setting a non-zero {@code maxAllocation} is also actively harmful here: Netty 4.1's
 * {@code HttpContentDecompressor.newContentDecoder} forwards it to {@code new BrotliDecoder(int)}, whose
 * single-argument form is an <em>input</em> buffer size, and {@code BrotliDecoder.handlerAdded} eagerly
 * allocates a native {@code DecoderJNI.Wrapper} of exactly that size - so a 256 MiB "ceiling" would cost
 * 256 MiB of direct memory for every {@code Content-Encoding: br} response, empty body included. This
 * class leaves {@code maxAllocation} at Netty's default of 0 and counts the bytes itself.
 *
 * <p>The counter is installed <em>inside</em> the decoder Netty builds per response, at the end of that
 * embedded pipeline, rather than downstream of this handler. {@code HttpContentDecoder} publishes inflated
 * content by firing it at its own outer {@link ChannelHandlerContext} from a forwarder appended to that
 * same embedded pipeline, so an override of {@code decode} would observe nothing. Hooking here also gets
 * the scope right for free: a decoder exists only for a body actually being inflated, and only for one
 * response, so an unencoded body is never counted (bounding those would fail large plain downloads the
 * caller asked for) and nothing has to be reset between responses on a pooled connection.
 *
 * <p>Not shareable: it holds the state of one connection's decoder.
 */
public class BoundedHttpContentDecompressor extends HttpContentDecompressor {

  private final boolean keepEncodingHeader;
  /**
   * Ceiling in bytes for a single response body; a non-positive value disables the check.
   */
  private final long maxDecompressedBytes;

  public BoundedHttpContentDecompressor(boolean keepEncodingHeader, long maxDecompressedBytes) {
    // Deliberately the no-arg super: see the class javadoc for why maxAllocation must stay 0.
    super();
    this.keepEncodingHeader = keepEncodingHeader;
    this.maxDecompressedBytes = maxDecompressedBytes;
  }

  @Override
  protected EmbeddedChannel newContentDecoder(String contentEncoding) throws Exception {
    EmbeddedChannel decoder = super.newContentDecoder(contentEncoding);
    if (decoder != null && maxDecompressedBytes > 0) {
      // Appended before HttpContentDecoder appends its own forwarder, so this sits between the
      // decompressor and the forwarder and sees every inflated buffer before it leaves the decoder.
      decoder.pipeline().addLast(new DecompressedSizeLimit());
    }
    return decoder;
  }

  @Override
  protected String getTargetContentEncoding(String contentEncoding) throws Exception {
    // keepEncodingHeader leaves the response advertising the encoding it arrived with, for callers that
    // inspect it; the default rewrites it to "identity" because the body downstream is no longer encoded.
    return keepEncodingHeader ? contentEncoding : super.getTargetContentEncoding(contentEncoding);
  }

  /**
   * Counts the inflated output of the response this decoder was created for, and fails the exchange once
   * it passes the ceiling.
   */
  private final class DecompressedSizeLimit extends ChannelInboundHandlerAdapter {

    private long decompressedBytes;
    private boolean exceeded;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (exceeded) {
        // Already failed. Tearing the decoder down calls finishAndReleaseAll(), which re-runs it over
        // whatever is left cumulated and would throw a second time out of cleanup; drop the leftovers
        // quietly instead so the caller sees the exception raised below.
        ReferenceCountUtil.release(msg);
        return;
      }

      if (msg instanceof ByteBuf) {
        decompressedBytes += ((ByteBuf) msg).readableBytes();
        if (decompressedBytes > maxDecompressedBytes) {
          exceeded = true;
          ReferenceCountUtil.release(msg);
          // EmbeddedChannel rethrows this out of the writeInbound driving the decoder, inside
          // HttpContentDecoder.decode; being a DecoderException it reaches AsyncHttpClientHandler's
          // exceptionCaught unwrapped, which fails the request and closes the connection.
          throw new DecompressionException("Decompressed response body exceeds the maximum of "
                  + maxDecompressedBytes + " bytes (" + decompressedBytes + " bytes decompressed so far)");
        }
      }

      ctx.fireChannelRead(msg);
    }
  }
}
