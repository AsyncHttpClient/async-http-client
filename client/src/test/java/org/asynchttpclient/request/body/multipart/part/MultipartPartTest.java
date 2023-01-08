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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.apache.commons.io.FileUtils;
import org.asynchttpclient.request.body.multipart.FileLikePart;
import org.asynchttpclient.request.body.multipart.MultipartBody;
import org.asynchttpclient.request.body.multipart.MultipartUtils;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.asynchttpclient.request.body.multipart.part.PartVisitor.CounterPartVisitor;
import org.asynchttpclient.test.TestUtils;

import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultipartPartTest {

    public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitStart() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[10])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitStart(counterVisitor);
            assertEquals(12, counterVisitor.getCount(), "CounterPartVisitor count for visitStart should match EXTRA_BYTES count plus boundary bytes count");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitStartZeroSizedByteArray() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitStart(counterVisitor);
            assertEquals(2, counterVisitor.getCount(), "CounterPartVisitor count for visitStart should match EXTRA_BYTES count when boundary byte array is of size zero");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitDispositionHeaderWithoutFileName() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitDispositionHeader(counterVisitor);
            assertEquals(45, counterVisitor.getCount(), "CounterPartVisitor count for visitDispositionHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_DISPOSITION_BYTES length + part name length when file name is not specified");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitDispositionHeaderWithFileName() {
        TestFileLikePart fileLikePart = new TestFileLikePart("baPart", null, null, null, null, "fileName");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitDispositionHeader(counterVisitor);
            assertEquals(68, counterVisitor.getCount(), "CounterPartVisitor count for visitDispositionHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_DISPOSITION_BYTES length + part name length + file name length when" + " both part name and file name are present");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitDispositionHeaderWithoutName() {
        // with fileName
        TestFileLikePart fileLikePart = new TestFileLikePart(null, null, null, null, null, "fileName");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitDispositionHeader(counterVisitor);
            assertEquals(53, counterVisitor.getCount(), "CounterPartVisitor count for visitDispositionHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_DISPOSITION_BYTES length + file name length when part name is not specified");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitContentTypeHeaderWithCharset() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null, "application/test", UTF_8, null, null);
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitContentTypeHeader(counterVisitor);
            assertEquals(47, counterVisitor.getCount(), "CounterPartVisitor count for visitContentTypeHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_TYPE_BYTES length + contentType length + charset length");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitContentTypeHeaderWithoutCharset() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null, "application/test");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitContentTypeHeader(counterVisitor);
            assertEquals(32, counterVisitor.getCount(), "CounterPartVisitor count for visitContentTypeHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_TYPE_BYTES length + contentType length when charset is not specified");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitTransferEncodingHeader() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null, null, null, null, "transferEncoding");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitTransferEncodingHeader(counterVisitor);
            assertEquals(45, counterVisitor.getCount(), "CounterPartVisitor count for visitTransferEncodingHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_TRANSFER_ENCODING_BYTES length + transferEncoding length");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitContentIdHeader() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null, null, null, "contentId");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitContentIdHeader(counterVisitor);
            assertEquals(23, counterVisitor.getCount(), "CounterPartVisitor count for visitContentIdHeader should be equal to"
                    + "CRLF_BYTES length + CONTENT_ID_BYTES length + contentId length");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitCustomHeadersWhenNoCustomHeaders() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null);
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitCustomHeaders(counterVisitor);
            assertEquals(0, counterVisitor.getCount(), "CounterPartVisitor count for visitCustomHeaders should be zero for visitCustomHeaders "
                    + "when there are no custom headers");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitCustomHeaders() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null);
        fileLikePart.addCustomHeader("custom-header", "header-value");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitCustomHeaders(counterVisitor);
            assertEquals(29, counterVisitor.getCount(), "CounterPartVisitor count for visitCustomHeaders should include the length of the custom headers");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitEndOfHeaders() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null);
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitEndOfHeaders(counterVisitor);
            assertEquals(4, counterVisitor.getCount(), "CounterPartVisitor count for visitEndOfHeaders should be equal to 4");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitPreContent() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name", "application/test", UTF_8, "contentId", "transferEncoding", "fileName");
        fileLikePart.addCustomHeader("custom-header", "header-value");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitPreContent(counterVisitor);
            assertEquals(216, counterVisitor.getCount(), "CounterPartVisitor count for visitPreContent should " + "be equal to the sum of the lengths of precontent");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testVisitPostContents() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null);
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, EMPTY_BYTE_ARRAY)) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitPostContent(counterVisitor);
            assertEquals(2, counterVisitor.getCount(), "CounterPartVisitor count for visitPostContent should be equal to 2");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void transferToShouldWriteStringPart() throws Exception {
        String text = FileUtils.readFileToString(TestUtils.resourceAsFile("test_sample_message.eml"), UTF_8);

        List<Part> parts = new ArrayList<>();
        parts.add(new StringPart("test_sample_message.eml", text));

        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("Cookie",
                "open-xchange-public-session-d41d8cd98f00b204e9800998ecf8427e=bfb98150b24f42bd844fc9ef2a9eaad5; open-xchange-secret-TSlq4Cm4nCBnDpBL1Px2A=9a49b76083e34c5ba2ef5c47362313fd; JSESSIONID=6883138728830405130.OX2");
        headers.set("Content-Length", "9241");
        headers.set("Content-Type", "multipart/form-data; boundary=5gigAKQyqDCVdlZ1fCkeLlEDDauTNoOOEhRnFg");
        headers.set("Host", "appsuite.qa.open-xchange.com");
        headers.set("Accept", "*/*");

        String boundary = "uwyqQolZaSmme019O2kFKvAeHoC14Npp";

        List<MultipartPart<? extends Part>> multipartParts = MultipartUtils.generateMultipartParts(parts, boundary.getBytes());
        try (MultipartBody multipartBody = new MultipartBody(multipartParts, "multipart/form-data; boundary=" + boundary, boundary.getBytes())) {

            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer(8 * 1024);
            multipartBody.transferTo(byteBuf);
            try {
                byteBuf.toString(UTF_8);
            } finally {
                byteBuf.release();
            }
        }
    }

    /**
     * Concrete implementation of {@link FileLikePart} for use in unit tests
     */
    private static class TestFileLikePart extends FileLikePart {

        TestFileLikePart(String name) {
            this(name, null, null, null, null);
        }

        TestFileLikePart(String name, String contentType) {
            this(name, contentType, null);
        }

        TestFileLikePart(String name, String contentType, Charset charset) {
            this(name, contentType, charset, null);
        }

        TestFileLikePart(String name, String contentType, Charset charset, String contentId) {
            this(name, contentType, charset, contentId, null);
        }

        TestFileLikePart(String name, String contentType, Charset charset, String contentId, String transferEncoding) {
            this(name, contentType, charset, contentId, transferEncoding, null);
        }

        TestFileLikePart(String name, String contentType, Charset charset, String contentId, String transferEncoding, String fileName) {
            super(name, contentType, charset, fileName, contentId, transferEncoding);
        }
    }

    /**
     * Concrete implementation of MultipartPart for use in unit tests.
     */
    private static class TestMultipartPart extends FileLikeMultipartPart<TestFileLikePart> {

        TestMultipartPart(TestFileLikePart part, byte[] boundary) {
            super(part, boundary);
        }

        @Override
        protected long getContentLength() {
            return 0;
        }

        @Override
        protected long transferContentTo(ByteBuf target) {
            return 0;
        }

        @Override
        protected long transferContentTo(WritableByteChannel target) {
            return 0;
        }
    }
}
