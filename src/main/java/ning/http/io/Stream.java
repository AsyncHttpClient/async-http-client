/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package ning.http.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Stream {
    /**
     * Copy data from the input stream to the output stream.
     *
     * @param in  the stream to read from
     * @param out the stream to handle to
     * @return number of bytes that were copied
     * @throws IOException
     */
    public static int copy(InputStream in, OutputStream out)
            throws IOException {
        byte[] buffer = new byte[16384];

        int total = 0;

        int read;
        while (true) {
            read = in.read(buffer, 0, buffer.length);

            if (read == -1) {
                break;
            }

            out.write(buffer, 0, read);
            total += read;
        }

        return total;
    }

    /**
     * Copy data from the input stream to the output stream. If there is more than limit number
     * of bytes in the input stream, then a {@link IllegalStateException} exception
     * is thrown.
     *
     * @param in    The stream to read from
     * @param out   The stream to handle to
     * @param limit The expected maximum number of bytes
     * @return Number of bytes that were copied
     */
    public static int copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[16384];

        int total = 0;

        int read;
        long capacity = limit;
        while (true) {
            read = in.read(buffer, 0, buffer.length);
            capacity -= read;
            if (capacity < 0) {
                throw new IllegalStateException(String.format("Only %d bytes expected in input stream", limit));
            }

            if (read == -1) {
                break;
            }

            out.write(buffer, 0, read);
            total += read;
        }

        return total;
    }

    /**
     * Copies up to limit bytes of data from the input stream to the output stream, and also
     * completely consumes the input stream.
     *
     * @param in    The stream to read from
     * @param out   The stream to handle to
     * @param limit The maximum number of bytes to handle to the output stream
     * @return Number of bytes that were copied
     */
    public static int consumeAndCopy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[16384];
        long capacity = limit;
        int total = 0;
        int read;

        while (true) {
            read = in.read(buffer, 0, buffer.length);

            if (read == -1) {
                break;
            }
            if (capacity > 0) {
                out.write(buffer, 0, read > capacity ? (int) capacity : read);
                capacity -= read;
            }
            total += read;
        }

        return total;
    }

    public static byte[] getBytes(InputStream in)
            throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Stream.copy(in, stream);

        return stream.toByteArray();
	}
}
