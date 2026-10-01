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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
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
 * {@link HttpContentDecompressor} configures it, so the decoded body and the errors are unchanged.
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
public class Http1ContentDecompressor extends HttpContentDecompressor {

    private static final int FHCRC = 0x02;
    private static final int FEXTRA = 0x04;
    private static final int FNAME = 0x08;
    private static final int FCOMMENT = 0x10;
    private static final int FRESERVED = 0xE0;

    // Same floor and forwarding threshold as JdkZlibDecoder, so the body is cut into parts the same way.
    private static final int MIN_OUTPUT_BUFFER_SIZE = 512;
    private static final int MAX_FORWARD_BYTES = 64 * 1024;

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

    // Inflaters between responses, per event loop. Holding them per connection instead would pin zlib's
    // native window on every idle pooled connection.
    private static final int MAX_IDLE_INFLATERS = 16;
    private static final FastThreadLocal<ArrayDeque<Inflater>> IDLE_NOWRAP_INFLATERS = idleInflaters();
    private static final FastThreadLocal<ArrayDeque<Inflater>> IDLE_ZLIB_INFLATERS = idleInflaters();

    private final CRC32 crc = new CRC32();

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
        if (msg instanceof HttpResponse) {
            endResponse();
            HttpResponse response = (HttpResponse) msg;
            String contentEncoding = response.headers().get(HttpHeaderNames.CONTENT_ENCODING);
            if (contentEncoding != null && response.status().code() != 100) {
                contentEncoding = contentEncoding.trim();
                if (isGzip(contentEncoding) || isDeflate(contentEncoding)) {
                    lastDecodedBySuper = false;
                    needRead = true;
                    startResponse(ctx, response, contentEncoding);
                    if (response instanceof HttpContent && !ctx.isRemoved()) {
                        decodeContent(ctx, (HttpContent) response);
                    }
                    return;
                }
            }
        } else if (inflating) {
            lastDecodedBySuper = false;
            needRead = true;
            decodeContent(ctx, (HttpContent) msg);
            return;
        }
        lastDecodedBySuper = true;
        super.decode(ctx, msg, out);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
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
            endResponse();
        } finally {
            super.handlerRemoved(ctx);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            endResponse();
        } finally {
            super.channelInactive(ctx);
        }
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
        if (in.isReadable()) {
            try {
                inflate(ctx, in);
            } catch (Throwable t) {
                endResponse();
                inflating = true;
                failed = true;
                throw t;
            }
            if (ctx.isRemoved()) {
                return;
            }
        }
        if (content instanceof LastHttpContent) {
            endResponse();
            HttpHeaders trailers = ((LastHttpContent) content).trailingHeaders();
            needRead = false;
            ctx.fireChannelRead(trailers.isEmpty()
                    ? LastHttpContent.EMPTY_LAST_CONTENT
                    : new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, trailers));
        }
    }

    private void inflate(ChannelHandlerContext ctx, ByteBuf content) {
        ByteBuf in = content;
        ByteBuf pending = this.pending;
        if (pending != null) {
            pending.writeBytes(content);
            in = pending;
        }

        for (;;) {
            int readable = in.readableBytes();
            inflateOnce(ctx, in);
            if (ctx.isRemoved()) {
                return;
            }
            if (!in.isReadable() || in.readableBytes() == readable) {
                break;
            }
        }

        if (pending != null) {
            if (pending.isReadable()) {
                pending.discardReadBytes();
            } else {
                pending.release();
                this.pending = null;
            }
        } else if (in.isReadable()) {
            // Only a gzip header or trailer that has not fully arrived is left over; the inflater consumes
            // all the deflate data it is given.
            this.pending = ctx.alloc().heapBuffer(in.readableBytes()).writeBytes(in);
        }
    }

    /**
     * One step of {@code JdkZlibDecoder#decode}, which {@code ByteToMessageDecoder} would call again for as
     * long as it consumes input.
     */
    private void inflateOnce(ChannelHandlerContext ctx, ByteBuf in) {
        if (finished) {
            in.skipBytes(in.readableBytes());
            return;
        }

        Inflater inflater = this.inflater;
        if (inflater == null) {
            if (in.readableBytes() < 2) {
                return;
            }
            // RFC 1950 says deflate is zlib-wrapped, but some servers send raw deflate.
            inflater = acquireInflater(!looksLikeZlib(in.getShort(in.readerIndex())));
        }

        if (gzip && gzipState != GzipState.HEADER_END) {
            if (gzipState == GzipState.FOOTER_START && !readGzipFooter(in, inflater)) {
                return;
            }
            if (!readGzipHeader(in) || !in.isReadable()) {
                return;
            }
        }

        int readable = in.readableBytes();
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
        try {
            boolean readFooter = false;
            // A full output buffer means the inflater may still hold decoded data after it has taken all the
            // input, so needsInput() alone would end the loop too early.
            boolean pendingOutput = false;
            while (pendingOutput || !inflater.needsInput()) {
                int preferredSize = Math.max(inflater.getRemaining() << 1, MIN_OUTPUT_BUFFER_SIZE);
                if (decompressed == null) {
                    decompressed = ctx.alloc().heapBuffer(preferredSize);
                } else {
                    decompressed.ensureWritable(preferredSize);
                }
                byte[] outArray = decompressed.array();
                int writerIndex = decompressed.writerIndex();
                int outIndex = decompressed.arrayOffset() + writerIndex;
                int writable = decompressed.writableBytes();
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
                        if (ctx.isRemoved()) {
                            // A handler further on took this one out of the pipeline, which already gave the
                            // inflater back.
                            return;
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

    private static FastThreadLocal<ArrayDeque<Inflater>> idleInflaters() {
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

    private void endResponse() {
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
