/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart.part;

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.netty.request.body.BodyChunkedInput;
import org.asynchttpclient.request.body.multipart.FilePart;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

public class FileMultipartPart extends FileLikeMultipartPart<FilePart> {

    private final long length;
    private FileChannel channel;
    private long position;

    public FileMultipartPart(FilePart part, byte[] boundary) {
        super(part, boundary);
        File file = part.getFile();
        if (!file.exists()) {
            throw new IllegalArgumentException("File part doesn't exist: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IllegalArgumentException("File part can't be read: " + file.getAbsolutePath());
        }
        length = file.length();
    }

    private FileChannel getChannel() throws IOException {
        if (channel == null) {
            channel = new RandomAccessFile(part.getFile(), "r").getChannel();
        }
        return channel;
    }

    @Override
    protected long getContentLength() {
        return length;
    }

    @Override
    protected long transferContentTo(ByteBuf target) throws IOException {
        // The length was taken when the request was built, and the Content-Length announces it. Bytes the file
        // gained since then would go out after the end of the part.
        int toRead = (int) Math.min(target.writableBytes(), length - position);
        int transferred = toRead > 0 ? target.writeBytes(getChannel(), toRead) : 0;
        if (transferred > 0) {
            position += transferred;
        } else if (transferred < 0) {
            throw endedEarly();
        }
        if (position == length) {
            state = MultipartState.POST_CONTENT;
            closeChannel();
        }
        return transferred;
    }

    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        long transferred = 0;
        if (position < length) {
            // WARN: don't use channel.position(), it's always 0 here
            // from FileChannel javadoc: "This method does not modify this channel's
            // position."
            FileChannel fileChannel = getChannel();
            long count = Math.min(BodyChunkedInput.DEFAULT_CHUNK_SIZE, length - position);
            transferred = fileChannel.transferTo(position, count, target);
            if (transferred > 0) {
                position += transferred;
            } else if (fileChannel.size() <= position) {
                // transferTo returns 0, not -1, at the end of the file. It also returns 0 when the target takes
                // nothing, so only the size of the open file tells the two apart.
                throw endedEarly();
            }
        }
        if (position == length) {
            state = MultipartState.POST_CONTENT;
            closeChannel();
        } else {
            slowTarget = true;
        }
        return transferred;
    }

    // An EOFException, which WriteListener never takes for a pooled connection that died: a retry would send the
    // shorter file under the length it has now.
    private EOFException endedEarly() {
        return new EOFException("Multipart file part '" + part.getName() + "' ended after " + position + " of its "
                + length + " bytes");
    }

    private void closeChannel() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    @Override
    public void close() {
        super.close();
        closeSilently(channel);
    }
}
