/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart.part;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;

import org.asynchttpclient.Param;
import org.asynchttpclient.request.body.multipart.FileLikePart;
import org.asynchttpclient.request.body.multipart.part.PartVisitor.ByteBufVisitor;
import org.asynchttpclient.request.body.multipart.part.PartVisitor.CounterPartVisitor;

public abstract class MultipartPart<T extends FileLikePart> implements Closeable {

    /**
     * Carriage return/linefeed as a byte array
     */
    private static final byte[] CRLF_BYTES = "\r\n".getBytes(US_ASCII);

    /**
     * Content disposition as a byte
     */
    private static final byte QUOTE_BYTE = '\"';

    /**
     * Extra characters as a byte array
     */
    private static final byte[] EXTRA_BYTES = "--".getBytes(US_ASCII);

    /**
     * Content disposition as a byte array
     */
    private static final byte[] CONTENT_DISPOSITION_BYTES = "Content-Disposition: ".getBytes(US_ASCII);

    /**
     * form-data as a byte array
     */
    private static final byte[] FORM_DATA_DISPOSITION_TYPE_BYTES = "form-data".getBytes(US_ASCII);

    /**
     * name as a byte array
     */
    private static final byte[] NAME_BYTES = "; name=".getBytes(US_ASCII);

    /**
     * Content type header as a byte array
     */
    private static final byte[] CONTENT_TYPE_BYTES = "Content-Type: ".getBytes(US_ASCII);

    /**
     * Content charset as a byte array
     */
    private static final byte[] CHARSET_BYTES = "; charset=".getBytes(US_ASCII);

    /**
     * Content type header as a byte array
     */
    private static final byte[] CONTENT_TRANSFER_ENCODING_BYTES = "Content-Transfer-Encoding: ".getBytes(US_ASCII);

    /**
     * Content type header as a byte array
     */
    private static final byte[] CONTENT_ID_BYTES = "Content-ID: ".getBytes(US_ASCII);

    /**
     * Attachment's file name as a byte array
     */
    private static final byte[] FILE_NAME_BYTES = "; filename=".getBytes(US_ASCII);

    protected final T part;
    protected final byte[] boundary;

    private final long length;
    private ByteBuf preContentBuffer;
    private ByteBuf postContentBuffer;
    protected MultipartState state;
    protected boolean slowTarget;

    public MultipartPart(T part, byte[] boundary) {
        this.part = part;
        this.boundary = boundary;
        preContentBuffer = computePreContentBytes();
        postContentBuffer = computePostContentBytes();
        length = preContentBuffer.readableBytes() + postContentBuffer.readableBytes() + getContentLength();
        state = MultipartState.PRE_CONTENT;
    }

    public final long length() {
        return length;
    }

    public MultipartState getState() {
        return state;
    }

    public boolean isTargetSlow() {
        return slowTarget;
    }

    public long transferTo(ByteBuf target) throws IOException {

        switch (state) {
        case DONE:
            return 0L;

        case PRE_CONTENT:
            return transfer(preContentBuffer, target, MultipartState.CONTENT);

        case CONTENT:
            return transferContentTo(target);

        case POST_CONTENT:
            return transfer(postContentBuffer, target, MultipartState.DONE);

        default:
            throw new IllegalStateException("Unknown state " + state);
        }
    }

    public long transferTo(WritableByteChannel target) throws IOException {
        slowTarget = false;

        switch (state) {
        case DONE:
            return 0L;

        case PRE_CONTENT:
            return transfer(preContentBuffer, target, MultipartState.CONTENT);

        case CONTENT:
            return transferContentTo(target);

        case POST_CONTENT:
            return transfer(postContentBuffer, target, MultipartState.DONE);

        default:
            throw new IllegalStateException("Unknown state " + state);
        }
    }

    @Override
    public void close() {
        preContentBuffer.release();
        postContentBuffer.release();
    }

    protected abstract long getContentLength();

    protected abstract long transferContentTo(ByteBuf target) throws IOException;

    protected abstract long transferContentTo(WritableByteChannel target) throws IOException;

    protected long transfer(ByteBuf source, ByteBuf target, MultipartState sourceFullyWrittenState) {

        int sourceRemaining = source.readableBytes();
        int targetRemaining = target.writableBytes();

        if (sourceRemaining <= targetRemaining) {
            target.writeBytes(source);
            state = sourceFullyWrittenState;
            return sourceRemaining;
        } else {
            target.writeBytes(source, targetRemaining - sourceRemaining);
            return targetRemaining;
        }
    }

    protected long transfer(ByteBuf source, WritableByteChannel target, MultipartState sourceFullyWrittenState) throws IOException {

        int transferred = 0;
        if (target instanceof GatheringByteChannel) {
            transferred = source.readBytes((GatheringByteChannel) target, (int) source.readableBytes());
        } else {
            for (ByteBuffer byteBuffer : source.nioBuffers()) {
                int len = byteBuffer.remaining();
                int written = target.write(byteBuffer);
                transferred += written;
                if (written != len) {
                    // couldn't write full buffer, exit loop
                    break;
                }
            }
            // assume this is a basic single ByteBuf
            source.readerIndex(source.readerIndex() + transferred);
        }

        if (source.isReadable()) {
            slowTarget = true;
        } else {
            state = sourceFullyWrittenState;
        }
        return transferred;
    }

    protected ByteBuf computePreContentBytes() {

        // compute length
        CounterPartVisitor counterVisitor = new CounterPartVisitor();
        visitPreContent(counterVisitor);
        long length = counterVisitor.getCount();

        // compute bytes
        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer((int) length);
        ByteBufVisitor bytesVisitor = new ByteBufVisitor(buffer);
        visitPreContent(bytesVisitor);
        return buffer;
    }

    protected ByteBuf computePostContentBytes() {

        // compute length
        CounterPartVisitor counterVisitor = new CounterPartVisitor();
        visitPostContent(counterVisitor);
        long length = counterVisitor.getCount();

        // compute bytes
        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer((int) length);
        ByteBufVisitor bytesVisitor = new ByteBufVisitor(buffer);
        visitPostContent(bytesVisitor);
        return buffer;
    }

    protected void visitStart(PartVisitor visitor) {
        visitor.withBytes(EXTRA_BYTES);
        visitor.withBytes(boundary);
    }

    protected void visitDispositionHeader(PartVisitor visitor) {
        visitor.withBytes(CRLF_BYTES);
        visitor.withBytes(CONTENT_DISPOSITION_BYTES);
        visitor.withBytes(part.getDispositionType() != null ? part.getDispositionType().getBytes(US_ASCII) : FORM_DATA_DISPOSITION_TYPE_BYTES);
        if (part.getName() != null) {
            visitor.withBytes(NAME_BYTES);
            visitor.withByte(QUOTE_BYTE);
            visitor.withBytes(part.getName().getBytes(US_ASCII));
            visitor.withByte(QUOTE_BYTE);
        }
        if (part.getFileName() != null) {
            visitor.withBytes(FILE_NAME_BYTES);
            visitor.withByte(QUOTE_BYTE);
            visitor.withBytes(part.getFileName().getBytes(part.getCharset() != null ? part.getCharset() : US_ASCII));
            visitor.withByte(QUOTE_BYTE);
        }
    }

    protected void visitContentTypeHeader(PartVisitor visitor) {
        String contentType = part.getContentType();
        if (contentType != null) {
            visitor.withBytes(CRLF_BYTES);
            visitor.withBytes(CONTENT_TYPE_BYTES);
            visitor.withBytes(contentType.getBytes(US_ASCII));
            Charset charSet = part.getCharset();
            if (charSet != null) {
                visitor.withBytes(CHARSET_BYTES);
                visitor.withBytes(part.getCharset().name().getBytes(US_ASCII));
            }
        }
    }

    protected void visitTransferEncodingHeader(PartVisitor visitor) {
        String transferEncoding = part.getTransferEncoding();
        if (transferEncoding != null) {
            visitor.withBytes(CRLF_BYTES);
            visitor.withBytes(CONTENT_TRANSFER_ENCODING_BYTES);
            visitor.withBytes(transferEncoding.getBytes(US_ASCII));
        }
    }

    protected void visitContentIdHeader(PartVisitor visitor) {
        String contentId = part.getContentId();
        if (contentId != null) {
            visitor.withBytes(CRLF_BYTES);
            visitor.withBytes(CONTENT_ID_BYTES);
            visitor.withBytes(contentId.getBytes(US_ASCII));
        }
    }

    protected void visitCustomHeaders(PartVisitor visitor) {
        if (isNonEmpty(part.getCustomHeaders())) {
            for (Param param : part.getCustomHeaders()) {
                visitor.withBytes(CRLF_BYTES);
                visitor.withBytes(param.getName().getBytes(US_ASCII));
                visitor.withBytes(param.getValue().getBytes(US_ASCII));
            }
        }
    }

    protected void visitEndOfHeaders(PartVisitor visitor) {
        visitor.withBytes(CRLF_BYTES);
        visitor.withBytes(CRLF_BYTES);
    }

    protected void visitPreContent(PartVisitor visitor) {
        visitStart(visitor);
        visitDispositionHeader(visitor);
        visitContentTypeHeader(visitor);
        visitTransferEncodingHeader(visitor);
        visitContentIdHeader(visitor);
        visitCustomHeaders(visitor);
        visitEndOfHeaders(visitor);
    }

    protected void visitPostContent(PartVisitor visitor) {
        visitor.withBytes(CRLF_BYTES);
    }
}
