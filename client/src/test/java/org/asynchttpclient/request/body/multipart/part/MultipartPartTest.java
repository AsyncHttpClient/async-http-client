package org.asynchttpclient.request.body.multipart.part;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;

import org.asynchttpclient.request.body.multipart.FileLikePart;
import org.asynchttpclient.request.body.multipart.part.PartVisitor.CounterPartVisitor;
import org.testng.annotations.Test;

import io.netty.buffer.ByteBuf;

public class MultipartPartTest {

    @Test
    public void testVisitStart() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[10])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitStart(counterVisitor);
            assertEquals(counterVisitor.getCount(), 12,
                    "CounterPartVisitor count for visitStart should match EXTRA_BYTES count plus boundary bytes count");
        }
    }

    @Test
    public void testVisitStartZeroSizedByteArray() {
        TestFileLikePart fileLikePart = new TestFileLikePart("Name");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitStart(counterVisitor);
            assertEquals(counterVisitor.getCount(), 2,
                    "CounterPartVisitor count for visitStart should match EXTRA_BYTES count when boundary byte array is of size zero");
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
            assertEquals(counterVisitor.getCount(), 68,
                    "CounterPartVisitor count for visitDispositionHeader should be equal to "
                            + "CRLF_BYTES length + CONTENT_DISPOSITION_BYTES length + part name length + file name length when"
                            + " both part name and file name are present");
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
            assertEquals(counterVisitor.getCount(), 0,
                    "CounterPartVisitor count for visitCustomHeaders should be zero for visitCustomHeaders "
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
            assertEquals(counterVisitor.getCount(), 27,
                    "CounterPartVisitor count for visitCustomHeaders should include the length of the custom headers");
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
        TestFileLikePart fileLikePart = new TestFileLikePart("Name", "application/test", UTF_8, "contentId", "transferEncoding",
                "fileName");
        fileLikePart.addCustomHeader("custom-header", "header-value");
        try (TestMultipartPart multipartPart = new TestMultipartPart(fileLikePart, new byte[0])) {
            CounterPartVisitor counterVisitor = new CounterPartVisitor();
            multipartPart.visitPreContent(counterVisitor);
            assertEquals(counterVisitor.getCount(), 214, "CounterPartVisitor count for visitPreContent should "
                    + "be equal to the sum of the lengths of precontent");
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

        public TestFileLikePart(String name, String contentType, Charset charset, String contentId, String transfertEncoding,
                String fileName) {
            super(name, contentType, charset, contentId, transfertEncoding);
            setFileName(fileName);
        }
    }

    /**
     * Concrete implementation of MultipartPart for use in unit tests.
     *
     */
    private class TestMultipartPart extends MultipartPart<TestFileLikePart> {

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
