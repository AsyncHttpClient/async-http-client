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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.asynchttpclient.request.body.multipart.FileLikePart;
import org.asynchttpclient.request.body.multipart.MultipartBody;
import org.asynchttpclient.request.body.multipart.MultipartUtils;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.asynchttpclient.request.body.multipart.part.PartVisitor.CounterPartVisitor;
import org.asynchttpclient.test.TestUtils;
import org.testng.annotations.Test;

public class MultipartPartTest {

    @Test
    public void testVisitStart() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[10])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitStart(counterVisitor);
            assertEquals(counterVisitor.getCount(), 12, "CounterPartVisitor count for visitStart should match EXTRA_BYTES count plus boundary bytes count");
        }
    }

    @Test
    public void testVisitStartZeroSizedByteArray() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitStart(counterVisitor);
            assertEquals(counterVisitor.getCount(), 2, "CounterPartVisitor count for visitStart should match EXTRA_BYTES count when boundary byte array is of size zero");
        }
    }

    @Test
    public void testVisitDispositionHeaderWithoutFileName() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitDispositionHeader(counterVisitor);
            assertEquals(counterVisitor.getCount(), 45, "CounterPartVisitor count for visitDispositionHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_DISPOSITION_BYTES length + part name length when file name is not specified");
        }
    }

    @Test
    public void testVisitDispositionHeaderWithFileName() {
        TestFileLikePart fileLikePart = new TestFileLikePart("baPart", null, null, null, null, "fileName");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitDispositionHeader(counterVisitor);
            assertEquals(counterVisitor.getCount(), 68, "CounterPartVisitor count for visitDispositionHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_DISPOSITION_BYTES length + part name length + file name length when" + " both part name and file name are present");
        }
    }

    @Test
    public void testVisitDispositionHeaderWithoutName() {
        // with fileName
        TestFileLikePart fileLikePart = new TestFileLikePart(null, null, null, null, null, "fileName");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitDispositionHeader(counterVisitor);
            assertEquals(counterVisitor.getCount(), 53, "CounterPartVisitor count for visitDispositionHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_DISPOSITION_BYTES length + file name length when part name is not specified");
        }
    }

    @Test
    public void testVisitContentTypeHeaderWithCharset() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null, "application/test", UTF_8, null, null);
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitContentTypeHeader(counterVisitor);
            assertEquals(counterVisitor.getCount(), 47, "CounterPartVisitor count for visitContentTypeHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_TYPE_BYTES length + contentType length + charset length");
        }
    }

    @Test
    public void testVisitContentTypeHeaderWithoutCharset() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null, "application/test");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitContentTypeHeader(counterVisitor);
            assertEquals(counterVisitor.getCount(), 32, "CounterPartVisitor count for visitContentTypeHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_TYPE_BYTES length + contentType length when charset is not specified");
        }
    }

    @Test
    public void testVisitTransferEncodingHeader() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null, null, null, null, "transferEncoding");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitTransferEncodingHeader(counterVisitor);
            assertEquals(counterVisitor.getCount(), 45, "CounterPartVisitor count for visitTransferEncodingHeader should be equal to "
                    + "CRLF_BYTES length + CONTENT_TRANSFER_ENCODING_BYTES length + transferEncoding length");
        }
    }

    @Test
    public void testVisitContentIdHeader() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null, null, null, "contentId");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitContentIdHeader(counterVisitor);
            assertEquals(counterVisitor.getCount(), 23, "CounterPartVisitor count for visitContentIdHeader should be equal to"
                    + "CRLF_BYTES length + CONTENT_ID_BYTES length + contentId length");
        }
    }

    @Test
    public void testVisitCustomHeadersWhenNoCustomHeaders() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null);
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitCustomHeaders(counterVisitor);
            assertEquals(counterVisitor.getCount(), 0, "CounterPartVisitor count for visitCustomHeaders should be zero for visitCustomHeaders "
                    + "when there are no custom headers");
        }
    }

    @Test
    public void testVisitCustomHeaders() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null);
        fileLikePart.addCustomHeader("custom-header", "header-value");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitCustomHeaders(counterVisitor);
            assertEquals(counterVisitor.getCount(), 29, "CounterPartVisitor count for visitCustomHeaders should include the length of the custom headers");
        }
    }

    @Test
    public void testVisitEndOfHeaders() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null);
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitEndOfHeaders(counterVisitor);
            assertEquals(counterVisitor.getCount(), 4, "CounterPartVisitor count for visitEndOfHeaders should be equal to 4");
        }
    }

    @Test
    public void testVisitPreContent() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name", "application/test", UTF_8, "contentId", "transferEncoding", "fileName");
        fileLikePart.addCustomHeader("custom-header", "header-value");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitPreContent(counterVisitor);
            assertEquals(counterVisitor.getCount(), 216, "CounterPartVisitor count for visitPreContent should " + "be equal to the sum of the lengths of precontent");
        }
    }

    @Test
    public void testVisitPostContents() {
        TestFileLikePart fileLikePart = new TestFileLikePart(null);
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitPostContent(counterVisitor);
            assertEquals(counterVisitor.getCount(), 2, "CounterPartVisitor count for visitPostContent should be equal to 2");
        }
    }

    @Test
    public void transferToShouldWriteStringPart() throws IOException, URISyntaxException {
        String text = FileUtils.readFileToString(TestUtils.resourceAsFile("test_sample_message.eml"));

        List<Part> parts = new ArrayList<>();
        parts.add(new StringPart("test_sample_message.eml", text));

        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(
                "Cookie",
                "open-xchange-public-session-d41d8cd98f00b204e9800998ecf8427e=bfb98150b24f42bd844fc9ef2a9eaad5; open-xchange-secret-TSlq4Cm4nCBnDpBL1Px2A=9a49b76083e34c5ba2ef5c47362313fd; JSESSIONID=6883138728830405130.OX2");
        headers.set("Content-Length", "9241");
        headers.set("Content-Type", "multipart/form-data; boundary=5gigAKQyqDCVdlZ1fCkeLlEDDauTNoOOEhRnFg");
        headers.set("Host", "appsuite.qa.open-xchange.com");
        headers.set("Accept", "*/*");

        String boundary = "uwyqQolZaSmme019O2kFKvAeHoC14Npp";

        List<MultipartPart<? extends Part>> multipartParts = MultipartUtils.generateMultipartParts(parts, boundary.getBytes());
        MultipartBody multipartBody = new MultipartBody(multipartParts, "multipart/form-data; boundary=" + boundary, boundary.getBytes());

        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer(8 * 1024);

        try {
            multipartBody.transferTo(byteBuf);
            byteBuf.toString(StandardCharsets.UTF_8);
        } finally {
            multipartBody.close();
            byteBuf.release();
        }
    }

    /**
     * Concrete implementation of {@link FileLikePart} for use in unit tests
     * 
     */
    private class TestFileLikePart extends FileLikePart {

        public TestFileLikePart(String name) {
            this(name, null, null, null, null);
        }

        public TestFileLikePart(String name, String contentType) {
            this(name, contentType, null);
        }

        public TestFileLikePart(String name, String contentType, Charset charset) {
            this(name, contentType, charset, null);
        }

        public TestFileLikePart(String name, String contentType, Charset charset, String contentId) {
            this(name, contentType, charset, contentId, null);
        }

        public TestFileLikePart(String name, String contentType, Charset charset, String contentId, String transfertEncoding) {
            this(name, contentType, charset, contentId, transfertEncoding, null);
        }

        public TestFileLikePart(String name, String contentType, Charset charset, String contentId, String transfertEncoding, String fileName) {
            super(name, contentType, charset, fileName, contentId, transfertEncoding);
        }
    }

    /**
     * Concrete implementation of MultipartPart for use in unit tests.
     *
     */
    private class TestMultipartPart extends FileLikeMultipartPart<TestFileLikePart> {

        public TestMultipartPart(TestFileLikePart part, byte[] boundary) {
            super(part, boundary);
        }

        @Override
        protected long getContentLength() {
            return 0;
        }

        @Override
        protected long transferContentTo(ByteBuf target) throws IOException {
            return 0;
        }

        @Override
        protected long transferContentTo(WritableByteChannel target) throws IOException {
            return 0;
        }
    }
}
