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
package org.asynchttpclient.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import org.asynchttpclient.ResponseBodyControl;
import org.asynchttpclient.netty.DiscardEvent;
import org.asynchttpclient.netty.NettyResponseBodyControl;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.channel.Channels;
import org.jetbrains.annotations.Nullable;

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * HTTP/1.1 content decompressor that bounds how far a response body may inflate, mirroring what
 * {@link Http2ContentDecompressor} enforces on HTTP/2 streams.
 * <p>
 * Netty's own {@code maxAllocation} argument is not such a bound. It caps the capacity of the one output
 * buffer produced by a single {@code ZlibDecoder.decode()} call, and nothing accumulates across calls.
 * AHC hands the decompressor one {@code HttpContent} of at most {@code httpClientCodecMaxChunkSize}
 * (8 KiB by default) at a time, so even DEFLATE's ~1032:1 ceiling keeps a single call's output around
 * 8 MiB — under any sane limit — while the response as a whole inflates without bound. The cap therefore
 * never fires on an ordinary decompression bomb.
 * <p>
 * {@code gzip}, {@code x-gzip}, {@code deflate} and {@code x-deflate} named by {@code Content-Encoding} are
 * inflated here directly. {@link HttpContentDecompressor} builds an {@link EmbeddedChannel} and a fresh
 * {@link Inflater} for every such response, and its {@code JdkZlibDecoder} copies each direct input buffer
 * onto the heap before inflating it. This handler borrows a reset {@link Inflater} from a small pool kept
 * per event loop for the length of one response, and feeds it the input buffer as it is. The format
 * handling (header and trailer checks, concatenated gzip members, zlib-or-raw detection for
 * {@code deflate}, a truncated stream ending quietly) follows {@code JdkZlibDecoder} as
 * {@link HttpContentDecompressor} configures it, so the decoded body and the errors are unchanged. These
 * encodings no longer go through {@link #newContentDecoder(String)}, and {@code java.util.zip} is used even when
 * {@code io.netty.noJdkZlibDecoder} asks Netty for JZlib.
 * <p>
 * Inflating {@code gzip} and {@code deflate} also follows the response's demand. {@link HttpContentDecompressor}
 * inflates each chunk completely as soon as it is read: suspending a response through
 * {@link ResponseBodyControl} stops further socket reads, but not that, so one read of a highly
 * compressible body could become tens of megabytes of body parts nobody had asked for. This handler hands the body on
 * in parts of at most 64 KiB, cut where {@code JdkZlibDecoder} would cut them, and checks before each part whether the
 * response is suspended. If it is, the input the inflater has not used yet stays here, together with every message
 * that arrived after it, and the next read request, such as the one {@code resume()} issues, inflates that input
 * before it is passed on to the transport. That is why this handler also sees outbound events; it passes all others
 * through. Only parts with content wait for a resume: once the body has been inflated in full, its end, with any
 * trailers, is handed on even to a suspended response, as for an unencoded body. If the connection closes while input
 * is held back, the rest of it is inflated and handed on at once, bounded only by {@code maxDecompressedBytes}.
 * <p>
 * Every other encoding still goes through {@link HttpContentDecompressor}. There the counting is done
 * inside the decoder's own {@link EmbeddedChannel} rather than around {@code HttpContentDecoder#decode}:
 * that class forwards decompressed output straight down the outer pipeline through an internal forwarder
 * installed at the end of the embedded pipeline, so the output never passes through the {@code decode}
 * out-list where it could be measured. Sitting between the decoder and that forwarder gives an exact count
 * of what decompression produced, and only of that, so an unencoded response, which never gets a decoder,
 * is passed through untouched and is never failed for being large; its size is the caller's own choice,
 * not a bomb.
 */
public class Http1ContentDecompressor extends HttpContentDecompressor implements ChannelOutboundHandler {

    private static final int FHCRC = 0x02;
    private static final int FEXTRA = 0x04;
    private static final int FNAME = 0x08;
    private static final int FCOMMENT = 0x10;
    private static final int FRESERVED = 0xE0;

    // Largest body part handed on at once, so that a suspension takes effect within one part.
    static final int MAX_PART_SIZE = 64 * 1024;

    // Same floor and forwarding threshold as JdkZlibDecoder, so the body is cut into parts the same way, except that
    // a part never grows past MAX_PART_SIZE.
    private static final int MIN_OUTPUT_BUFFER_SIZE = 512;
    private static final int MAX_FORWARD_BYTES = Math.min(MAX_PART_SIZE,
            SystemPropertyUtil.getInt("io.netty.compression.defaultMaxForwardBytes", 64 * 1024));

    // Inflaters between responses, per event loop. Holding them per connection instead would pin zlib's
    // native window on every idle pooled connection.
    private static final int MAX_IDLE_INFLATERS = 16;
    private static final FastThreadLocal<ArrayDeque<Inflater>> IDLE_NOWRAP_INFLATERS = newIdleInflaterPool();
    private static final FastThreadLocal<ArrayDeque<Inflater>> IDLE_ZLIB_INFLATERS = newIdleInflaterPool();

    private enum GzipState {
        HEADER_START,
        FLG_READ,
        XLEN_READ,
        SKIP_FNAME,
        SKIP_COMMENT,
        PROCESS_FHCRC,
        HEADER_END,
        FOOTER_START,
    }

    private final boolean keepEncodingHeader;
    // Maximum cumulative decompressed bytes for one response; 0 disables the limit.
    private final long maxDecompressedBytes;

    private final CRC32 crc = new CRC32();

    // Bumped whenever the state of a response is dropped. A handler further on may end the response from
    // inside a call that forwards decoded content, and the decoding that made the call must then stop.
    private int generation;
    // Whether the channel was active when the message being decoded arrived. The last part of a body that
    // ends with the connection is decoded after the channel has gone inactive, and must still be decoded.
    private boolean activeAtDecode;
    // The response being inflated by this class rather than by HttpContentDecompressor.
    private boolean inflating;
    private boolean gzip;
    // Null until the first two bytes of a deflate body have told zlib from raw deflate.
    private @Nullable Inflater inflater;
    private boolean nowrap;
    private boolean failed;
    private boolean finished;
    private GzipState gzipState = GzipState.HEADER_START;
    private int flags = -1;
    private int xlen = -1;
    private long decompressedBytes;
    // Bytes of a gzip header or trailer split across two HttpContent messages.
    private @Nullable ByteBuf pending;

    // HttpContentDecoder keeps its read-request flag private, so channelReadComplete defers to it only when
    // the last message of the read was its own.
    private boolean lastDecodedBySuper = true;
    private boolean needRead = true;

    // Whether the response was suspended before the body was used up. The rest of the body waits here: the input
    // not yet inflated, and every message that arrived after it.
    private boolean paused;
    // Messages that arrived while paused, in order, each holding a reference.
    private final ArrayDeque<HttpObject> queued = new ArrayDeque<>();
    // The input the inflater had not used when the response paused. The inflater may still point into it, so it is
    // kept until the inflater has used it up.
    private @Nullable ByteBuf heldInput;
    // Whether the inflater still held output when the response paused, which it hands out without further input.
    private boolean heldOutput;
    // The end of the body, if it arrived with the input that is held.
    private @Nullable LastHttpContent heldLast;
    private boolean draining;
    private boolean readPending;
    private boolean inputClosed;
    // Whether nobody reads the rest of the current body, so its input is dropped instead of inflated.
    private boolean discarding;

    public Http1ContentDecompressor(boolean keepEncodingHeader, long maxDecompressedBytes) {
        // maxAllocation=0 is what the no-arg HttpContentDecompressor() constructor passes, so a single
        // decode call behaves exactly as it did before any bound was attempted. The counter below is what
        // enforces the limit.
        super(0);
        this.keepEncodingHeader = keepEncodingHeader;
        this.maxDecompressedBytes = maxDecompressedBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
        if (paused || draining) {
            // It waits behind the paused body.
            lastDecodedBySuper = false;
            queued.add(ReferenceCountUtil.retain(msg));
            return;
        }
        decodeMessage(ctx, msg, out);
    }

    private void decodeMessage(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
        if (msg instanceof HttpResponse) {
            endResponse();
            HttpResponse response = (HttpResponse) msg;
            String contentEncoding = response.headers().get(HttpHeaderNames.CONTENT_ENCODING);
            if (contentEncoding != null && response.status().code() != 100) {
                contentEncoding = contentEncoding.trim();
                if (isGzip(contentEncoding) || isDeflate(contentEncoding)) {
                    lastDecodedBySuper = false;
                    activeAtDecode = ctx.channel().isActive();
                    needRead = true;
                    int generation = this.generation;
                    startResponse(ctx, response, contentEncoding);
                    if (!abandoned(ctx, generation) && response instanceof HttpContent) {
                        decodeContent(ctx, (HttpContent) response);
                    }
                    return;
                }
            }
        } else if (inflating && msg instanceof HttpContent) {
            lastDecodedBySuper = false;
            activeAtDecode = ctx.channel().isActive();
            needRead = true;
            decodeContent(ctx, (HttpContent) msg);
            return;
        }
        lastDecodedBySuper = true;
        super.decode(ctx, msg, out);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (paused) {
            // A read now would let more input in while the response is suspended.
            needRead = true;
            ctx.fireChannelReadComplete();
            return;
        }
        if (lastDecodedBySuper) {
            super.channelReadComplete(ctx);
            return;
        }
        boolean needRead = this.needRead;
        this.needRead = true;
        try {
            ctx.fireChannelReadComplete();
        } finally {
            // A message that was swallowed whole, such as a chunk holding only part of a gzip header, gives
            // the handlers after this one nothing to answer with a read of their own.
            if (needRead && !ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        try {
            releaseQueued();
            endResponse();
        } finally {
            super.handlerRemoved(ctx);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // The pipeline is about to be torn down, so what is held back is handed on now, even to a suspended
        // response.
        inputClosed = true;
        try {
            if (paused && !draining) {
                readPending = false;
                resume(ctx);
            }
        } catch (Throwable cause) {
            ctx.fireExceptionCaught(cause);
        } finally {
            try {
                releaseQueued();
                endResponse();
            } finally {
                super.channelInactive(ctx);
            }
        }
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        if (paused || draining) {
            // Input that was already read is used up before the transport is asked for more.
            readPending = true;
            if (!draining) {
                resume(ctx);
            }
        } else {
            ctx.read();
        }
    }

    /**
     * Hands on what was held back, until the response is suspended again before its next part.
     */
    private void resume(ChannelHandlerContext ctx) throws Exception {
        draining = true;
        try {
            activeAtDecode = ctx.channel().isActive();
            if (continueBody(ctx)) {
                HttpObject msg;
                while (!paused && (msg = queued.poll()) != null) {
                    decodeQueued(ctx, msg);
                }
            }
        } finally {
            draining = false;
        }
        if (!paused && readPending) {
            readPending = false;
            ctx.read();
        }
    }

    // Returns false if the response was suspended again before the held input was used up.
    private boolean continueBody(ChannelHandlerContext ctx) {
        paused = false;
        ByteBuf in = heldInput;
        if (in == null) {
            return true;
        }
        heldInput = null;
        int generation = this.generation;
        boolean usedUp;
        try {
            usedUp = inflate(ctx, in, true, generation);
        } catch (Throwable t) {
            failResponse();
            throw t;
        }
        if (abandoned(ctx, generation)) {
            return true;
        }
        if (!usedUp) {
            paused = true;
            return false;
        }
        LastHttpContent last = heldLast;
        if (last != null) {
            heldLast = null;
            endBody(ctx, last);
        }
        return true;
    }

    // Decodes a message that waited while the response was paused, as channelRead would have.
    private void decodeQueued(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        List<Object> out = new ArrayList<>(1);
        try {
            decodeMessage(ctx, msg, out);
        } finally {
            ReferenceCountUtil.release(msg);
            for (Object decoded : out) {
                ctx.fireChannelRead(decoded);
            }
        }
    }

    private void releaseQueued() {
        for (HttpObject msg; (msg = queued.poll()) != null; ) {
            ReferenceCountUtil.release(msg);
        }
        paused = false;
        readPending = false;
    }

    /**
     * Whether the rest of the current body is discarded: the exchange is over, or the channel is being drained, for
     * example after a redirect, an authentication challenge or a replay.
     */
    private static boolean isUnwanted(Channel channel) {
        Object attribute = Channels.getAttribute(channel);
        return attribute == DiscardEvent.DISCARD || attribute instanceof OnLastHttpContentCallback
                || attribute instanceof NettyResponseFuture && ((NettyResponseFuture<?>) attribute).isDone();
    }

    private static boolean isSuspended(Channel channel) {
        Object attribute = Channels.getAttribute(channel);
        return attribute instanceof NettyResponseFuture
                && NettyResponseBodyControl.isSuspended((NettyResponseFuture<?>) attribute, channel);
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) {
        ctx.bind(localAddress, promise);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                        ChannelPromise promise) {
        ctx.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
        ctx.disconnect(promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        ctx.close(promise);
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) {
        ctx.deregister(promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        ctx.write(msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    protected EmbeddedChannel newContentDecoder(String contentEncoding) throws Exception {
        EmbeddedChannel decoder = super.newContentDecoder(contentEncoding);
        if (decoder != null && maxDecompressedBytes > 0) {
            // Appended before HttpContentDecoder appends its forwarder, so every decompressed buffer is
            // counted before it leaves for the outer pipeline.
            decoder.pipeline().addLast(new DecompressedSizeLimiter());
        }
        return decoder;
    }

    @Override
    protected String getTargetContentEncoding(String contentEncoding) throws Exception {
        // Leaving Content-Encoding in place lets the caller see what the server sent, at the cost of a
        // header that no longer describes the body handed up. Opt-in via keepEncodingHeader.
        return keepEncodingHeader ? contentEncoding : super.getTargetContentEncoding(contentEncoding);
    }

    private static boolean isGzip(String contentEncoding) {
        return HttpHeaderValues.GZIP.contentEqualsIgnoreCase(contentEncoding)
                || HttpHeaderValues.X_GZIP.contentEqualsIgnoreCase(contentEncoding);
    }

    private static boolean isDeflate(String contentEncoding) {
        return HttpHeaderValues.DEFLATE.contentEqualsIgnoreCase(contentEncoding)
                || HttpHeaderValues.X_DEFLATE.contentEqualsIgnoreCase(contentEncoding);
    }

    /**
     * Rewrites the headers exactly as {@code HttpContentDecoder} does once it has installed a decoder.
     */
    private void startResponse(ChannelHandlerContext ctx, HttpResponse response, String contentEncoding)
            throws Exception {
        inflating = true;
        gzip = isGzip(contentEncoding);
        if (gzip) {
            acquireInflater(true);
        }

        HttpHeaders headers = response.headers();
        // The decoded length is known only once the whole body is through.
        if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            headers.remove(HttpHeaderNames.CONTENT_LENGTH);
            headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        String targetContentEncoding = getTargetContentEncoding(contentEncoding);
        if (HttpHeaderValues.IDENTITY.contentEquals(targetContentEncoding)) {
            headers.remove(HttpHeaderNames.CONTENT_ENCODING);
        } else {
            headers.set(HttpHeaderNames.CONTENT_ENCODING, targetContentEncoding);
        }

        HttpResponse head = response;
        if (response instanceof HttpContent) {
            // The body is decoded and sent separately, and a message that carries content of its own would
            // read downstream as the end of the response.
            head = new DefaultHttpResponse(response.protocolVersion(), response.status());
            head.headers().set(headers);
            head.setDecoderResult(response.decoderResult());
        }
        needRead = false;
        ctx.fireChannelRead(head);
    }

    private void decodeContent(ChannelHandlerContext ctx, HttpContent content) {
        if (failed) {
            // The exchange has already been failed; what is still in flight for it is dropped.
            return;
        }
        ByteBuf in = content.content();
        if (in.isReadable() && !discarding) {
            int generation = this.generation;
            boolean usedUp;
            try {
                usedUp = inflate(ctx, in, generation);
            } catch (Throwable t) {
                failResponse();
                throw t;
            }
            if (abandoned(ctx, generation)) {
                return;
            }
            if (!usedUp) {
                paused = true;
                if (content instanceof LastHttpContent) {
                    heldLast = trailer((LastHttpContent) content);
                }
                return;
            }
        }
        if (content instanceof LastHttpContent) {
            endBody(ctx, trailer((LastHttpContent) content));
        }
    }

    private void endBody(ChannelHandlerContext ctx, LastHttpContent last) {
        endResponse();
        needRead = false;
        ctx.fireChannelRead(last);
    }

    // The end of the body, with its trailers and its decoder result, which marks a chunk HttpObjectDecoder could not
    // parse. Dropping a failed result would turn a broken body into a complete one.
    private static LastHttpContent trailer(LastHttpContent last) {
        HttpHeaders trailers = last.trailingHeaders();
        if (trailers.isEmpty() && last.decoderResult().isSuccess()) {
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }
        LastHttpContent trailer = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, trailers);
        trailer.setDecoderResult(last.decoderResult());
        return trailer;
    }

    /**
     * Inflates {@code content}. Returns {@code false} if the response was suspended before the input was used up;
     * what is left of it is then held until the response resumes.
     */
    private boolean inflate(ChannelHandlerContext ctx, ByteBuf content, int generation) {
        ByteBuf pending = this.pending;
        if (pending == null) {
            return inflate(ctx, content, false, generation);
        }
        this.pending = null;
        pending.writeBytes(content);
        return inflate(ctx, pending, true, generation);
    }

    // An owned input is released here; one that is not belongs to the message being decoded.
    private boolean inflate(ChannelHandlerContext ctx, ByteBuf in, boolean owned, int generation) {
        try {
            for (;;) {
                int readable = in.readableBytes();
                boolean usedUp = inflateOnce(ctx, in, generation);
                if (abandoned(ctx, generation)) {
                    return true;
                }
                if (discarding) {
                    in.skipBytes(in.readableBytes());
                    return true;
                }
                if (!usedUp) {
                    // The inflater may still point into this input, so it is kept, not copied.
                    heldInput = owned ? in.retain() : in.retainedSlice();
                    return false;
                }
                if (!in.isReadable() || in.readableBytes() == readable) {
                    break;
                }
            }
            if (in.isReadable()) {
                // Only a gzip header or trailer that has not fully arrived, or the first byte of a deflate body, is
                // left over; the inflater consumes all the deflate data it is given.
                this.pending = ctx.alloc().heapBuffer(in.readableBytes()).writeBytes(in);
            }
            return true;
        } finally {
            if (owned) {
                in.release();
            }
        }
    }

    /**
     * One step of {@code JdkZlibDecoder#decode}, which {@code ByteToMessageDecoder} would call again for as
     * long as it consumes input. Returns {@code false} if it stopped before the next part because the response is
     * suspended.
     */
    private boolean inflateOnce(ChannelHandlerContext ctx, ByteBuf in, int generation) {
        if (finished) {
            in.skipBytes(in.readableBytes());
            return true;
        }

        Inflater inflater = this.inflater;
        if (inflater == null) {
            if (in.readableBytes() < 2) {
                return true;
            }
            // RFC 1950 says deflate is zlib-wrapped, but some servers send raw deflate.
            inflater = acquireInflater(!looksLikeZlib(in.getShort(in.readerIndex())));
        }

        if (gzip && gzipState != GzipState.HEADER_END) {
            if (gzipState == GzipState.FOOTER_START && !readGzipFooter(in, inflater)) {
                return true;
            }
            if (!readGzipHeader(in) || !in.isReadable()) {
                return true;
            }
        }

        int readable = in.readableBytes();
        // The inflater keeps pointing at this input after the chunk is released. That is safe only because
        // nothing reads from it again before the next setInput() or reset(): the loop below runs until the
        // input is used up, the stream has finished or the response is suspended, a finished gzip member is reset
        // by its trailer, and input left by a suspension is held and set again before the next inflate().
        if (in.hasArray()) {
            inflater.setInput(in.array(), in.arrayOffset() + in.readerIndex(), readable);
        } else if (in.nioBufferCount() == 1) {
            inflater.setInput(in.nioBuffer(in.readerIndex(), readable));
        } else {
            byte[] array = new byte[readable];
            in.getBytes(in.readerIndex(), array);
            inflater.setInput(array);
        }

        ByteBuf decompressed = null;
        boolean forward = false;
        boolean suspended = false;
        try {
            boolean readFooter = false;
            // A full output buffer means the inflater may still hold decoded data after it has taken all the
            // input, so needsInput() alone would end the loop too early.
            boolean pendingOutput = heldOutput;
            heldOutput = false;
            while (pendingOutput || !inflater.needsInput()) {
                if (decompressed == null) {
                    if (isUnwanted(ctx.channel())) {
                        // Nobody reads the rest of this body, so inflating it would be wasted work, and could fail
                        // the drain at the size limit. Its end is still handed on, so a drain callback still runs.
                        discarding = true;
                        break;
                    }
                    if (!inputClosed && isSuspended(ctx.channel())) {
                        // The next part waits for a resume, and so does the output the inflater still holds.
                        suspended = true;
                        heldOutput = pendingOutput;
                        break;
                    }
                    decompressed = ctx.alloc().heapBuffer(preferredOutputBufferSize(inflater));
                }
                byte[] outArray = decompressed.array();
                int writerIndex = decompressed.writerIndex();
                int outIndex = decompressed.arrayOffset() + writerIndex;
                // Never more than is left of a part. Parts are handed on by MAX_PART_SIZE at the latest, so this is
                // never zero, whatever the forwarding threshold.
                int writable = Math.min(decompressed.writableBytes(),
                        MAX_PART_SIZE - decompressed.readableBytes());
                int outputLength = inflater.inflate(outArray, outIndex, writable);
                pendingOutput = outputLength == writable;
                if (outputLength > 0) {
                    decompressedBytes += outputLength;
                    if (maxDecompressedBytes > 0 && decompressedBytes > maxDecompressedBytes) {
                        throw new DecompressionException(
                                "HTTP/1.1 response body exceeds the maximum decompressed size of "
                                + maxDecompressedBytes + " bytes");
                    }
                    decompressed.writerIndex(writerIndex + outputLength);
                    if (gzip) {
                        crc.update(outArray, outIndex, outputLength);
                    }
                    if (decompressed.readableBytes() >= MAX_FORWARD_BYTES) {
                        ByteBuf buffer = decompressed;
                        decompressed = null;
                        fireContent(ctx, buffer);
                        if (abandoned(ctx, generation)) {
                            // The inflater has already been given back.
                            return true;
                        }
                    }
                } else if (inflater.needsDictionary()) {
                    throw new DecompressionException(
                            "decompression failure, unable to set dictionary as non was specified");
                }

                if (inflater.finished()) {
                    if (gzip) {
                        readFooter = true;
                    } else {
                        finished = true;
                    }
                    break;
                }
                // Grown after every unfinished step, as ZlibDecoder.prepareDecompressBuffer does, so a buffer
                // that cannot grow fails at the same point: since Netty 4.2.19, only once it is also full.
                if (decompressed != null
                        && decompressed.ensureWritable(preferredOutputBufferSize(inflater), true) == 1
                        && !decompressed.isWritable()) {
                    throw new DecompressionException(
                            "Decompression buffer has reached maximum size: " + decompressed.maxCapacity());
                }
            }

            in.skipBytes(readable - inflater.getRemaining());

            if (readFooter) {
                gzipState = GzipState.FOOTER_START;
                readGzipFooter(in, inflater);
            }
            forward = true;
        } catch (DataFormatException e) {
            throw new DecompressionException("decompression failure", e);
        } finally {
            if (decompressed != null) {
                if (forward && decompressed.isReadable()) {
                    fireContent(ctx, decompressed);
                } else {
                    decompressed.release();
                }
            }
        }
        return !suspended;
    }

    /**
     * Whether the response being decoded when content was last forwarded is over. Removing this handler
     * ends it at once. Closing the channel only queues the inactive event, so a channel that went inactive
     * during the forward ends the response here, and what is left of its body is not inflated.
     */
    private boolean abandoned(ChannelHandlerContext ctx, int generation) {
        if (generation != this.generation) {
            return true;
        }
        if (activeAtDecode && !ctx.channel().isActive()) {
            failResponse();
            return true;
        }
        return false;
    }

    // Drops the response's state; whatever is still in flight for it is then discarded.
    private void failResponse() {
        endResponse();
        inflating = true;
        failed = true;
    }

    private static int preferredOutputBufferSize(Inflater inflater) {
        return Math.max(inflater.getRemaining() << 1, MIN_OUTPUT_BUFFER_SIZE);
    }

    private void fireContent(ChannelHandlerContext ctx, ByteBuf buffer) {
        needRead = false;
        ctx.fireChannelRead(new DefaultHttpContent(buffer));
    }

    private boolean readGzipHeader(ByteBuf in) {
        switch (gzipState) {
            case HEADER_START:
                if (in.readableBytes() < 10) {
                    return false;
                }
                int magic0 = in.readByte();
                int magic1 = in.readByte();
                // Like JdkZlibDecoder, only the first magic byte is checked.
                if (magic0 != 31) {
                    throw new DecompressionException("Input is not in the GZIP format");
                }
                crc.update(magic0);
                crc.update(magic1);

                int method = in.readUnsignedByte();
                if (method != Deflater.DEFLATED) {
                    throw new DecompressionException(
                            "Unsupported compression method " + method + " in the GZIP header");
                }
                crc.update(method);

                flags = in.readUnsignedByte();
                crc.update(flags);
                if ((flags & FRESERVED) != 0) {
                    throw new DecompressionException("Reserved flags are set in the GZIP header");
                }

                // MTIME, XFL and OS.
                updateCrc(in, in.readerIndex(), 6);
                in.skipBytes(6);
                gzipState = GzipState.FLG_READ;
                // fall through
            case FLG_READ:
                if ((flags & FEXTRA) != 0) {
                    if (in.readableBytes() < 2) {
                        return false;
                    }
                    int xlen1 = in.readUnsignedByte();
                    int xlen2 = in.readUnsignedByte();
                    crc.update(xlen1);
                    crc.update(xlen2);
                    xlen = xlen2 << 8 | xlen1;
                }
                gzipState = GzipState.XLEN_READ;
                // fall through
            case XLEN_READ:
                if (xlen != -1) {
                    if (in.readableBytes() < xlen) {
                        return false;
                    }
                    updateCrc(in, in.readerIndex(), xlen);
                    in.skipBytes(xlen);
                }
                gzipState = GzipState.SKIP_FNAME;
                // fall through
            case SKIP_FNAME:
                if (!skipIfNeeded(in, FNAME)) {
                    return false;
                }
                gzipState = GzipState.SKIP_COMMENT;
                // fall through
            case SKIP_COMMENT:
                if (!skipIfNeeded(in, FCOMMENT)) {
                    return false;
                }
                gzipState = GzipState.PROCESS_FHCRC;
                // fall through
            case PROCESS_FHCRC:
                if ((flags & FHCRC) != 0) {
                    if (in.readableBytes() < 2) {
                        return false;
                    }
                    int crc16Value = in.readUnsignedShortLE();
                    int readCrc16 = (int) (crc.getValue() & 0xFFFF);
                    if (crc16Value != readCrc16) {
                        throw new DecompressionException(
                                "CRC16 value mismatch. Expected: " + crc16Value + ", Got: " + readCrc16);
                    }
                }
                crc.reset();
                gzipState = GzipState.HEADER_END;
                // fall through
            case HEADER_END:
                return true;
            default:
                throw new IllegalStateException();
        }
    }

    private boolean skipIfNeeded(ByteBuf in, int flagMask) {
        if ((flags & flagMask) != 0) {
            for (;;) {
                if (!in.isReadable()) {
                    return false;
                }
                int b = in.readUnsignedByte();
                crc.update(b);
                if (b == 0x00) {
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Checks CRC32 and ISIZE, then readies the inflater for a further gzip member, which
     * {@link HttpContentDecompressor} decodes too.
     */
    private boolean readGzipFooter(ByteBuf in, Inflater inflater) {
        if (in.readableBytes() < 8) {
            return false;
        }
        long crcValue = in.readUnsignedIntLE();
        long readCrc = crc.getValue();
        if (crcValue != readCrc) {
            throw new DecompressionException("CRC value mismatch. Expected: " + crcValue + ", Got: " + readCrc);
        }
        int dataLength = in.readIntLE();
        int readLength = inflater.getTotalOut();
        if (dataLength != readLength) {
            throw new DecompressionException(
                    "Number of bytes mismatch. Expected: " + dataLength + ", Got: " + readLength);
        }
        inflater.reset();
        crc.reset();
        xlen = -1;
        gzipState = GzipState.HEADER_START;
        return true;
    }

    private void updateCrc(ByteBuf in, int index, int length) {
        if (in.hasArray()) {
            crc.update(in.array(), in.arrayOffset() + index, length);
        } else {
            crc.update(in.nioBuffer(index, length));
        }
    }

    // RFC 1950 section 2.2, written as JdkZlibDecoder writes it so the same bodies are taken for zlib.
    private static boolean looksLikeZlib(short cmfFlg) {
        return (cmfFlg & 0x7800) == 0x7800 && cmfFlg % 31 == 0;
    }

    private static FastThreadLocal<ArrayDeque<Inflater>> newIdleInflaterPool() {
        return new FastThreadLocal<ArrayDeque<Inflater>>() {
            @Override
            protected ArrayDeque<Inflater> initialValue() {
                return new ArrayDeque<>();
            }

            @Override
            protected void onRemoval(ArrayDeque<Inflater> inflaters) {
                for (Inflater inflater; (inflater = inflaters.pollFirst()) != null; ) {
                    inflater.end();
                }
            }
        };
    }

    private Inflater acquireInflater(boolean nowrap) {
        Inflater inflater = (nowrap ? IDLE_NOWRAP_INFLATERS : IDLE_ZLIB_INFLATERS).get().pollLast();
        if (inflater == null) {
            inflater = new Inflater(nowrap);
        }
        this.inflater = inflater;
        this.nowrap = nowrap;
        return inflater;
    }

    // Visible for tests: whether the inflater still held output when the response paused, and how much input.
    boolean holdsInflaterOutput() {
        return heldOutput;
    }

    int heldInputBytes() {
        ByteBuf in = heldInput;
        return in == null ? -1 : in.readableBytes();
    }

    // Visible for tests.
    static ArrayDeque<Inflater> idleInflaters(boolean nowrap) {
        return (nowrap ? IDLE_NOWRAP_INFLATERS : IDLE_ZLIB_INFLATERS).get();
    }

    private void endResponse() {
        generation++;
        Inflater inflater = this.inflater;
        if (inflater != null) {
            this.inflater = null;
            // Also drops the inflater's reference to the last input buffer, which is released after decode.
            inflater.reset();
            ArrayDeque<Inflater> idle = (nowrap ? IDLE_NOWRAP_INFLATERS : IDLE_ZLIB_INFLATERS).get();
            if (idle.size() < MAX_IDLE_INFLATERS) {
                idle.offerLast(inflater);
            } else {
                inflater.end();
            }
        }
        ByteBuf pending = this.pending;
        if (pending != null) {
            pending.release();
            this.pending = null;
        }
        ByteBuf heldInput = this.heldInput;
        if (heldInput != null) {
            heldInput.release();
            this.heldInput = null;
        }
        heldOutput = false;
        heldLast = null;
        discarding = false;
        inflating = false;
        failed = false;
        finished = false;
        gzipState = GzipState.HEADER_START;
        flags = -1;
        xlen = -1;
        decompressedBytes = 0;
        crc.reset();
    }

    /**
     * Fails the response once its decompressed body passes {@link #maxDecompressedBytes}. The thrown
     * {@link DecompressionException} is recorded by the {@link EmbeddedChannel} and rethrown out of the
     * {@code writeInbound} call driving the decoder, so it surfaces exactly like a corrupt-body error and
     * fails the exchange — the same exception type the HTTP/2 path uses.
     */
    private final class DecompressedSizeLimiter extends ChannelInboundHandlerAdapter {

        private long totalDecompressedBytes;
        private boolean exceeded;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (exceeded) {
                // The decoder is torn down with finishAndReleaseAll(), which re-runs it over anything left
                // cumulated. Drop that quietly: throwing a second time out of cleanup would replace the
                // error the caller sees and can leak the decoder itself.
                ReferenceCountUtil.release(msg);
                return;
            }

            if (msg instanceof ByteBuf) {
                totalDecompressedBytes += ((ByteBuf) msg).readableBytes();
                if (totalDecompressedBytes > maxDecompressedBytes) {
                    exceeded = true;
                    ReferenceCountUtil.release(msg);
                    throw new DecompressionException("HTTP/1.1 response body exceeds the maximum decompressed size of "
                            + maxDecompressedBytes + " bytes");
                }
            }

            ctx.fireChannelRead(msg);
        }
    }
}
