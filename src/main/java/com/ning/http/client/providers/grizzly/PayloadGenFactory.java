/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.providers.grizzly;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.Param;
import com.ning.http.client.Request;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.multipart.MultipartBody;
import com.ning.http.client.multipart.MultipartUtils;
import com.ning.http.client.multipart.Part;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.FileTransfer;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.Charsets;

import static com.ning.http.client.providers.grizzly.PayloadGenerator.MAX_CHUNK_SIZE;
import static com.ning.http.util.MiscUtils.isNonEmpty;

/**
 * {@link PayloadGenerator} factory.
 * 
 * @author Grizzly team
 */
final class PayloadGenFactory {
    
    private static final PayloadGenerator[] HANDLERS =
            new PayloadGenerator[]{
                new StringPayloadGenerator(), 
                new ByteArrayPayloadGenerator(),
                new ParamsPayloadGenerator(),
                new StreamDataPayloadGenerator(),
                new PartsPayloadGenerator(), 
                new FilePayloadGenerator(),
                new BodyGeneratorAdapter()};
    
    public static PayloadGenerator wrapWithExpect(final PayloadGenerator generator) {
        return new ExpectWrapper(generator);
    }
    
    public static PayloadGenerator getPayloadGenerator(final Request request) {
        for (final PayloadGenerator h : HANDLERS) {
            if (h.handlesPayloadType(request)) {
                return h;
            }
        }
        
        return null;
    }
    
    private static final class ExpectWrapper extends PayloadGenerator {

        final PayloadGenerator delegate;
        Request request;
        HttpRequestPacket requestPacket;

        // -------------------------------------------------------- Constructors


        private ExpectWrapper(final PayloadGenerator delegate) {

            this.delegate = delegate;

        }


        // -------------------------------------------- Methods from PayloadGenerator


        public boolean handlesPayloadType(Request request) {
            return delegate.handlesPayloadType(request);
        }

        @SuppressWarnings({"unchecked"})
        public boolean generate(final FilterChainContext ctx,
                final Request request, final HttpRequestPacket requestPacket)
                throws IOException {
            
            this.request = request;
            this.requestPacket = requestPacket;
            
            // Set content-length if possible
            final long contentLength = delegate.getContentLength(request);
            if (contentLength != -1) {
                requestPacket.setContentLengthLong(contentLength);
            }
            
            ctx.write(requestPacket,
                    ((!requestPacket.isCommitted())
                            ? ctx.getTransportContext().getCompletionHandler()
                            : null));
            return true;
        }

        public void continueConfirmed(final FilterChainContext ctx) throws IOException {
            delegate.generate(ctx, request, requestPacket);
        }

    } // END ContinueHandler


    private static final class ByteArrayPayloadGenerator extends PayloadGenerator {


        // -------------------------------------------- Methods from BodyGenerator

        public boolean handlesPayloadType(final Request request) {
            return (request.getByteData() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean generate(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final MemoryManager mm = ctx.getMemoryManager();
            final byte[] data = request.getByteData();
            final Buffer gBuffer = Buffers.wrap(mm, data);
            if (requestPacket.getContentLength() == -1) {
                requestPacket.setContentLengthLong(data.length);
            }
            final HttpContent content = requestPacket.httpContentBuilder()
                    .content(gBuffer)
                    .last(true)
                    .build();
            
            ctx.write(content, ((!requestPacket.isCommitted())
                    ? ctx.getTransportContext().getCompletionHandler()
                    : null));
            return true;
        }
        
        @Override
        protected long getContentLength(final Request request) {
            return request.getContentLength() >= 0
                    ? request.getContentLength()
                    : request.getByteData().length;
        }        
    }


    private static final class StringPayloadGenerator extends PayloadGenerator {


        // -------------------------------------------- Methods from PayloadGenerator


        public boolean handlesPayloadType(final Request request) {
            return (request.getStringData() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean generate(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            String charset = request.getBodyEncoding();
            if (charset == null) {
                charset = Charsets.ASCII_CHARSET.name();
            }
            final byte[] data = request.getStringData().getBytes(charset);
            final MemoryManager mm = ctx.getMemoryManager();
            final Buffer gBuffer = Buffers.wrap(mm, data);
            if (requestPacket.getContentLength() == -1) {
                requestPacket.setContentLengthLong(data.length);
            }
            final HttpContent content = requestPacket.httpContentBuilder()
                    .content(gBuffer)
                    .last(true)
                    .build();
            ctx.write(content, ((!requestPacket.isCommitted())
                    ? ctx.getTransportContext().getCompletionHandler()
                    : null));
            return true;
        }

    } // END StringPayloadGenerator


    private static final class ParamsPayloadGenerator extends PayloadGenerator {


        // -------------------------------------------- Methods from PayloadGenerator


        public boolean handlesPayloadType(final Request request) {
            return isNonEmpty(request.getFormParams());
        }

        @SuppressWarnings({"unchecked"})
        public boolean generate(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            if (requestPacket.getContentType() == null) {
                requestPacket.setContentType("application/x-www-form-urlencoded");
            }
            String charset = request.getBodyEncoding();
            if (charset == null) {
                charset = Charsets.ASCII_CHARSET.name();
            }
            
            if (isNonEmpty(request.getFormParams())) {
                StringBuilder sb = new StringBuilder(128);
                for (Param param : request.getFormParams()) {
                    String name = URLEncoder.encode(param.getName(), charset);
                    String value = URLEncoder.encode(param.getValue(), charset);
                    sb.append(name).append('=').append(value).append('&');
                }
                sb.setLength(sb.length() - 1);
                final byte[] data = sb.toString().getBytes(charset);
                final MemoryManager mm = ctx.getMemoryManager();
                final Buffer gBuffer = Buffers.wrap(mm, data);
                final HttpContent content = requestPacket.httpContentBuilder()
                        .content(gBuffer)
                        .last(true)
                        .build();
                if (requestPacket.getContentLength() == -1) {
                    requestPacket.setContentLengthLong(data.length);
                }
                ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            }
            
            return true;
        }

    } // END ParamsPayloadGenerator

    private static final class StreamDataPayloadGenerator extends PayloadGenerator {

        // -------------------------------------------- Methods from PayloadGenerator


        public boolean handlesPayloadType(final Request request) {
            return (request.getStreamData() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean generate(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final MemoryManager mm = ctx.getMemoryManager();
            Buffer buffer = mm.allocate(512);
            final byte[] b = new byte[512];
            int read;
            final InputStream in = request.getStreamData();
            try {
                in.reset();
            } catch (IOException ioe) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(ioe.toString(), ioe);
                }
            }
            if (in.markSupported()) {
                in.mark(0);
            }

            while ((read = in.read(b)) != -1) {
                if (read > buffer.remaining()) {
                    buffer = mm.reallocate(buffer, buffer.capacity() + 512);
                }
                buffer.put(b, 0, read);
            }
            buffer.trim();
            if (buffer.hasRemaining()) {
                final HttpContent content = requestPacket.httpContentBuilder()
                        .content(buffer)
                        .last(true)
                        .build();
                buffer.allowBufferDispose(false);
                ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            }
            
            return true;
        }

    } // END StreamDataPayloadGenerator


    private static final class PartsPayloadGenerator extends PayloadGenerator {

        // -------------------------------------------- Methods from PayloadGenerator


        public boolean handlesPayloadType(final Request request) {
            return isNonEmpty(request.getParts());
        }

        public boolean generate(final FilterChainContext ctx,
                                final Request request,
                                final HttpRequestPacket requestPacket)
                throws IOException {

            final List<Part> parts = request.getParts();
            final MultipartBody multipartBody = MultipartUtils.newMultipartBody(parts, request.getHeaders());
            final long contentLength = multipartBody.getContentLength();
            final String contentType = multipartBody.getContentType();
            requestPacket.setContentLengthLong(contentLength);
            requestPacket.setContentType(contentType);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("REQUEST(modified): contentLength={}, contentType={}", new Object[]{requestPacket.getContentLength(), requestPacket.getContentType()});
            }

            final FeedableBodyGenerator generator = new FeedableBodyGenerator() {
                @Override
                public Body createBody() throws IOException {
                    return multipartBody;
                }
            };
            generator.setFeeder(new FeedableBodyGenerator.BaseFeeder(generator) {
                @Override
                public void flush() throws IOException {
                    final Body bodyLocal = feedableBodyGenerator.createBody();
                    try {
                        final MemoryManager mm = ctx.getMemoryManager();
                        boolean last = false;
                        while (!last) {
                            Buffer buffer = mm.allocate(PayloadGenerator.MAX_CHUNK_SIZE);
                            buffer.allowBufferDispose(true);
                            final long readBytes = bodyLocal.read(buffer.toByteBuffer());
                            if (readBytes > 0) {
                                buffer.position((int) readBytes);
                                buffer.trim();
                            } else {
                                buffer.dispose();
                                if (readBytes < 0) {
                                    last = true;
                                    buffer = Buffers.EMPTY_BUFFER;
                                } else {
                                    throw new IllegalStateException("MultipartBody unexpectedly returned 0 bytes available");
                                }
                            }
                            feed(buffer, last);
                        }
                    } finally {
                        if (bodyLocal != null) {
                            try {
                                bodyLocal.close();
                            } catch (IOException ignore) {
                            }
                        }
                    }
                }
            });
            generator.initializeAsynchronousTransfer(ctx, requestPacket);
            return false;
        }

    } // END PartsPayloadGenerator


    private static final class FilePayloadGenerator extends PayloadGenerator {
        private static final boolean SEND_FILE_SUPPORT;
        static {
            SEND_FILE_SUPPORT = /*configSendFileSupport()*/ false;
        }

        // -------------------------------------------- Methods from PayloadGenerator


        public boolean handlesPayloadType(final Request request) {
            return (request.getFile() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean generate(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final File f = request.getFile();
            requestPacket.setContentLengthLong(f.length());
            final HttpTransactionContext context =
                    HttpTransactionContext.currentTransaction(requestPacket);
            
            if (!SEND_FILE_SUPPORT || requestPacket.isSecure()) {
                
                final FileInputStream fis = new FileInputStream(request.getFile());
                final MemoryManager mm = ctx.getMemoryManager();
                AtomicInteger written = new AtomicInteger();
                boolean last = false;
                try {
                    for (byte[] buf = new byte[MAX_CHUNK_SIZE]; !last; ) {
                        Buffer b = null;
                        int read;
                        if ((read = fis.read(buf)) < 0) {
                            last = true;
                            b = Buffers.EMPTY_BUFFER;
                        }
                        if (b != Buffers.EMPTY_BUFFER) {
                            written.addAndGet(read);
                            b = Buffers.wrap(mm, buf, 0, read);
                        }

                        final HttpContent content =
                                requestPacket.httpContentBuilder().content(b).
                                        last(last).build();
                        ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
                    }
                } finally {
                    try {
                        fis.close();
                    } catch (IOException ignored) {
                    }
                }
            } else {
                // write the headers
                ctx.write(requestPacket, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
                ctx.write(new FileTransfer(f), new EmptyCompletionHandler<WriteResult>() {

                    @Override
                    public void updated(WriteResult result) {
                        final AsyncHandler ah = context.getAsyncHandler();
                        if (ah instanceof TransferCompletionHandler) {
                            final long written = result.getWrittenSize();
                            context.totalBodyWritten += written;
                            final long total = context.totalBodyWritten;
                            ((TransferCompletionHandler) ah).onContentWriteProgress(
                                    written,
                                    total,
                                    requestPacket.getContentLength());
                        }
                    }
                });
            }

            return true;
        }

        @Override
        protected long getContentLength(final Request request) {
            return request.getContentLength() >= 0
                    ? request.getContentLength()
                    : request.getFile().length();
        }        
    } // END FilePayloadGenerator


    private static final class BodyGeneratorAdapter extends PayloadGenerator {

        // -------------------------------------------- Methods from PayloadGenerator


        public boolean handlesPayloadType(final Request request) {
            return (request.getBodyGenerator() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean generate(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final BodyGenerator generator = request.getBodyGenerator();
            final Body bodyLocal = generator.createBody();
            final long len = bodyLocal.getContentLength();
            if (len >= 0) {
                requestPacket.setContentLengthLong(len);
            } else {
                requestPacket.setChunked(true);
            }

            final MemoryManager mm = ctx.getMemoryManager();
            boolean last = false;

            while (!last) {
                Buffer buffer = mm.allocate(MAX_CHUNK_SIZE);
                buffer.allowBufferDispose(true);
                
                final long readBytes = bodyLocal.read(buffer.toByteBuffer());
                if (readBytes > 0) {
                    buffer.position((int) readBytes);
                    buffer.trim();
                } else {
                    buffer.dispose();
                    
                    if (readBytes < 0) {
                        last = true;
                        buffer = Buffers.EMPTY_BUFFER;
                    } else {
                        // pass the context to bodyLocal to be able to
                        // continue body transferring once more data is available
                        if (generator instanceof FeedableBodyGenerator) {
                            ((FeedableBodyGenerator) generator).initializeAsynchronousTransfer(ctx, requestPacket);
                            return false;
                        } else {
                            throw new IllegalStateException("BodyGenerator unexpectedly returned 0 bytes available");
                        }
                    }
                }

                final HttpContent content =
                        requestPacket.httpContentBuilder().content(buffer).
                                last(last).build();
                ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            }
            
            return true;
        }

    } // END BodyGeneratorAdapter        
}
