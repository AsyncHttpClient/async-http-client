/*
 * Copyright (c) 2013-2014 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly.bodyhandler;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.providers.grizzly.HttpTxContext;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.FileTransfer;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;

public final class FileBodyHandler extends BodyHandler {

    private static final boolean SEND_FILE_SUPPORT;
    static {
        SEND_FILE_SUPPORT = configSendFileSupport();
    }

    private final boolean compressionEnabled;

    public FileBodyHandler(
            final GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider) {
        compressionEnabled = grizzlyAsyncHttpProvider.getClientConfig().isCompressionEnforced();
    }
    
    // ------------------------------------------------ Methods from BodyHandler

    public boolean handlesBodyType(final Request request) {
        return request.getFile() != null;
    }

    @SuppressWarnings({ "unchecked" })
    public boolean doHandle(final FilterChainContext ctx, final Request request, final HttpRequestPacket requestPacket) throws IOException {

        final File f = request.getFile();
        requestPacket.setContentLengthLong(f.length());
        final HttpTxContext context = HttpTxContext.get(ctx);
        if (compressionEnabled || !SEND_FILE_SUPPORT || requestPacket.isSecure()) {
            final FileInputStream fis = new FileInputStream(request.getFile());
            final MemoryManager mm = ctx.getMemoryManager();
            AtomicInteger written = new AtomicInteger();
            boolean last = false;
            try {
                for (byte[] buf = new byte[MAX_CHUNK_SIZE]; !last;) {
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

                    final HttpContent content = requestPacket.httpContentBuilder().content(b).last(last).build();
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
                    notifyHandlerIfNeeded(context, requestPacket, result);
                }

                @Override
                public void completed(WriteResult result) {
                    notifyHandlerIfNeeded(context, requestPacket, result);
                }
            });
        }

        return true;
    }

    @Override
    protected long getContentLength(final Request request) {
        if (request.getContentLength() >= 0) {
            return request.getContentLength();
        }
        
        return compressionEnabled ? -1 : request.getFile().length();
    }
    
    // --------------------------------------------------------- Private Methods

    private static void notifyHandlerIfNeeded(final HttpTxContext context, final HttpRequestPacket requestPacket,
            final WriteResult writeResult) {
        final AsyncHandler handler = context.getHandler();
        if (handler != null) {
            if (handler instanceof TransferCompletionHandler) {
                // WriteResult keeps a track of the total amount written,
                // so we need to calculate the delta ourselves.
                final long resultTotal = writeResult.getWrittenSize();
                final long written = (resultTotal - context.getTotalBodyWritten().get());
                final long total = context.getTotalBodyWritten().addAndGet(written);
                ((TransferCompletionHandler) handler).onContentWriteProgress(written, total, requestPacket.getContentLength());
            }
        }
    }

    private static boolean configSendFileSupport() {
        return !((System.getProperty("os.name").equalsIgnoreCase("linux") && !linuxSendFileSupported()) || System.getProperty("os.name")
                .equalsIgnoreCase("HP-UX"));
    }

    private static boolean linuxSendFileSupported() {
        final String version = System.getProperty("java.version");
        if (version.startsWith("1.6")) {
            int idx = version.indexOf('_');
            if (idx == -1) {
                return false;
            }
            final int patchRev = Integer.parseInt(version.substring(idx + 1));
            return (patchRev >= 18);
        } else {
            return version.startsWith("1.7") || version.startsWith("1.8");
        }
    }

} // END FileBodyHandler
