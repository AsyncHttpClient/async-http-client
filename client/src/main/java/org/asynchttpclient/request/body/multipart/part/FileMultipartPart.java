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
        // can return -1 if file is empty or FileChannel was closed
        int transferred = target.writeBytes(getChannel(), target.writableBytes());
        if (transferred > 0) {
            position += transferred;
        }
        if (position == length || transferred < 0) {
            state = MultipartState.POST_CONTENT;
            if (channel.isOpen()) {
                channel.close();
            }
        }
        return transferred;
    }

    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        // WARN: don't use channel.position(), it's always 0 here
        // from FileChannel javadoc: "This method does not modify this channel's
        // position."
        long transferred = getChannel().transferTo(position, BodyChunkedInput.DEFAULT_CHUNK_SIZE, target);
        if (transferred > 0) {
            position += transferred;
        }
        if (position == length || transferred < 0) {
            state = MultipartState.POST_CONTENT;
            if (channel.isOpen()) {
                channel.close();
            }
        } else {
            slowTarget = true;
        }
        return transferred;
    }

    @Override
    public void close() {
        super.close();
        closeSilently(channel);
    }
}
