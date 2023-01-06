package org.asynchttpclient.request.body.multipart;

import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

class InputStreamSupplierPartTest {

    private byte counter = 0;
    private final Supplier<InputStream> inputStreamSupplier = () -> new ByteArrayInputStream(new byte[]{counter++});

    @BeforeTest
    public void setup() {
        counter = 0;
    }

    @Test
    public void testParametersAreDelegated() {
        String name = "testName";
        String fileName = "testFileName";
        long contentLength = 1;
        String contentType = "text/plain";
        Charset charset = StandardCharsets.UTF_8;
        String contentId = "testContentId";
        String transferEncoding = StandardCharsets.UTF_8.toString();

        InputStreamSupplierPart part = new InputStreamSupplierPart(name, inputStreamSupplier, fileName, contentLength,
                contentType, charset, contentId, transferEncoding);
        InputStreamPart delegatedPart = part.createInputStreamPart();
        assertEquals(delegatedPart.getName(), name);
        assertEquals(delegatedPart.getFileName(), fileName);
        assertEquals(delegatedPart.getContentLength(), contentLength);
        assertEquals(delegatedPart.getContentType(), contentType);
        assertEquals(delegatedPart.getCharset(), charset);
        assertEquals(delegatedPart.getContentId(), contentId);
        assertEquals(delegatedPart.getTransferEncoding(), transferEncoding);
    }

    @Test
    public void testFreshInputStreamOnUse() throws IOException {
        InputStreamSupplierPart part = new InputStreamSupplierPart("name", inputStreamSupplier, "fileName");

        InputStreamPart firstCreatedPart = part.createInputStreamPart();
        InputStreamPart secondCreatedPart = part.createInputStreamPart();
        assertNotEquals(firstCreatedPart, secondCreatedPart);

        InputStream firstInputStream = firstCreatedPart.getInputStream();
        InputStream secondInputStream = secondCreatedPart.getInputStream();
        try {
            assertNotEquals(firstInputStream, secondInputStream);

            assertEquals(readAll(firstInputStream), new byte[]{0});
            assertEquals(readAll(secondInputStream), new byte[]{1});
        } finally {
            firstInputStream.close();
            secondInputStream.close();
        }
    }

    private byte[] readAll(InputStream inputStream) throws IOException {
        byte[] targetArray = new byte[inputStream.available()];
        inputStream.read(targetArray);
        return targetArray;
    }
}
