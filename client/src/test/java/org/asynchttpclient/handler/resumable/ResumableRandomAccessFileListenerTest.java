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
package org.asynchttpclient.handler.resumable;

import io.github.artsok.RepeatedIfExceptionsTest;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class ResumableRandomAccessFileListenerTest {

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testOnBytesReceivedBufferHasArray() throws IOException {
        RandomAccessFile file = mock(RandomAccessFile.class);
        ResumableRandomAccessFileListener listener = new ResumableRandomAccessFileListener(file);
        byte[] array = {1, 2, 23, 33};
        ByteBuffer buf = ByteBuffer.wrap(array);
        listener.onBytesReceived(buf);
        verify(file).write(array, 0, 4);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testOnBytesReceivedBufferHasNoArray() throws IOException {
        RandomAccessFile file = mock(RandomAccessFile.class);
        ResumableRandomAccessFileListener listener = new ResumableRandomAccessFileListener(file);

        byte[] byteArray = {1, 2, 23, 33};
        ByteBuffer buf = ByteBuffer.allocateDirect(4);
        buf.put(byteArray);
        buf.flip();
        listener.onBytesReceived(buf);
        verify(file).write(byteArray);
    }
}
