/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.async;

import static org.asynchttpclient.async.util.TestUtils.findFreePort;
import static org.asynchttpclient.async.util.TestUtils.getClasspathFile;
import static org.asynchttpclient.async.util.TestUtils.newJettyHttpServer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.Streams;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.multipart.ByteArrayPart;
import org.asynchttpclient.multipart.FilePart;
import org.asynchttpclient.multipart.StringPart;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.StandardCharsets;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * @author dominict
 */
public abstract class MultipartUploadTest extends AbstractBasicTest {
    public static byte GZIPTEXT[] = new byte[] { 31, -117, 8, 8, 11, 43, 79, 75, 0, 3, 104, 101, 108, 108, 111, 46, 116, 120, 116, 0, -53, 72, -51, -55, -55, -25, 2, 0, 32, 48,
            58, 54, 6, 0, 0, 0 };

    @BeforeClass
    public void setUp() throws Exception {
        port1 = findFreePort();

        server = newJettyHttpServer(port1);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.addServlet(new ServletHolder(new MockMultipartUploadServlet()), "/upload/*");

        server.setHandler(context);
        server.start();
    }

    /**
     * Tests that the streaming of a file works.
     */
    @Test
    public void testSendingSmallFilesAndByteArray() {
        String expectedContents = "filecontent: hello";
        String expectedContents2 = "gzipcontent: hello";
        String expectedContents3 = "filecontent: hello2";
        String testResource1 = "textfile.txt";
        String testResource2 = "gzip.txt.gz";
        String testResource3 = "textfile2.txt";

        File testResource1File = null;
        try {
            testResource1File = getClasspathFile(testResource1);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            fail("unable to find " + testResource1);
        }

        File testResource2File = null;
        try {
            testResource2File = getClasspathFile(testResource2);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            fail("unable to find " + testResource2);
        }

        File testResource3File = null;
        try {
            testResource3File = getClasspathFile(testResource3);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            fail("unable to find " + testResource3);
        }

        List<File> testFiles = new ArrayList<File>();
        testFiles.add(testResource1File);
        testFiles.add(testResource2File);
        testFiles.add(testResource3File);

        List<String> expected = new ArrayList<String>();
        expected.add(expectedContents);
        expected.add(expectedContents2);
        expected.add(expectedContents3);

        List<Boolean> gzipped = new ArrayList<Boolean>();
        gzipped.add(false);
        gzipped.add(true);
        gzipped.add(false);

        boolean tmpFileCreated = false;
        File tmpFile = null;
        FileOutputStream os = null;
        try {
            tmpFile = File.createTempFile("textbytearray", ".txt");
            os = new FileOutputStream(tmpFile);
            IOUtils.write(expectedContents.getBytes(StandardCharsets.UTF_8), os);
            tmpFileCreated = true;

            testFiles.add(tmpFile);
            expected.add(expectedContents);
            gzipped.add(false);

        } catch (FileNotFoundException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } finally {
            if (os != null) {
                IOUtils.closeQuietly(os);
            }
        }

        if (!tmpFileCreated) {
            fail("Unable to test ByteArrayMultiPart, as unable to write to filesystem the tmp test content");
        }

        AsyncHttpClientConfig.Builder bc = new AsyncHttpClientConfig.Builder();

        bc.setFollowRedirect(true);

        AsyncHttpClient c = getAsyncHttpClient(bc.build());

        try {

            RequestBuilder builder = new RequestBuilder("POST");
            builder.setUrl("http://localhost" + ":" + port1 + "/upload/bob");
            builder.addBodyPart(new FilePart("file1", testResource1File, "text/plain", StandardCharsets.UTF_8.name()));
            builder.addBodyPart(new FilePart("file2", testResource2File, "application/x-gzip", null));
            builder.addBodyPart(new StringPart("Name", "Dominic", StandardCharsets.UTF_8.name()));
            builder.addBodyPart(new FilePart("file3", testResource3File, "text/plain", StandardCharsets.UTF_8.name()));

            builder.addBodyPart(new StringPart("Age", "3", AsyncHttpProviderUtils.DEFAULT_CHARSET.name()));
            builder.addBodyPart(new StringPart("Height", "shrimplike", AsyncHttpProviderUtils.DEFAULT_CHARSET.name()));
            builder.addBodyPart(new StringPart("Hair", "ridiculous", AsyncHttpProviderUtils.DEFAULT_CHARSET.name()));

            builder.addBodyPart(new ByteArrayPart("file4", expectedContents.getBytes(StandardCharsets.UTF_8), "text/plain", StandardCharsets.UTF_8.name(), "bytearray.txt"));

            Request r = builder.build();

            Response res = c.executeRequest(r).get();

            assertEquals(res.getStatusCode(), 200);

            testSentFile(expected, testFiles, res, gzipped);

            c.close();
        } catch (Exception e) {
            e.printStackTrace();
            fail("Download Exception");
        } finally {
            c.close();
            FileUtils.deleteQuietly(tmpFile);
        }
    }

    /**
     * Test that the files were sent, based on the response from the servlet
     * 
     * @param expectedContents
     * @param sourceFiles
     * @param r
     * @param deflate
     */
    private void testSentFile(List<String> expectedContents, List<File> sourceFiles, Response r, List<Boolean> deflate) {
        String content = null;
        try {
            content = r.getResponseBody();
            assertNotNull("===>" + content);
            logger.debug(content);
        } catch (IOException e) {
            fail("Unable to obtain content");
        }

        String[] contentArray = content.split("\\|\\|");
        // TODO: this fail on win32
        assertEquals(contentArray.length, 2);

        String tmpFiles = contentArray[1];
        assertNotNull(tmpFiles);
        assertTrue(tmpFiles.trim().length() > 2);
        tmpFiles = tmpFiles.substring(1, tmpFiles.length() - 1);

        String[] responseFiles = tmpFiles.split(",");
        assertNotNull(responseFiles);
        assertEquals(responseFiles.length, sourceFiles.size());

        logger.debug(Arrays.toString(responseFiles));

        int i = 0;
        for (File sourceFile : sourceFiles) {

            FileInputStream instream = null;
            File tmp = null;
            try {

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] sourceBytes = null;
                try {
                    instream = new FileInputStream(sourceFile);
                    byte[] buf = new byte[8092];
                    int len = 0;
                    while ((len = instream.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                    logger.debug("================");
                    logger.debug("Length of file: " + baos.toByteArray().length);
                    logger.debug("Contents: " + Arrays.toString(baos.toByteArray()));
                    logger.debug("================");
                    System.out.flush();
                    sourceBytes = baos.toByteArray();
                } finally {
                    IOUtils.closeQuietly(instream);
                }

                tmp = new File(responseFiles[i].trim());
                logger.debug("==============================");
                logger.debug(tmp.getAbsolutePath());
                logger.debug("==============================");
                System.out.flush();
                assertTrue(tmp.exists());

                instream = new FileInputStream(tmp);
                ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
                byte[] buf = new byte[8092];
                int len = 0;
                while ((len = instream.read(buf)) > 0) {
                    baos2.write(buf, 0, len);
                }
                IOUtils.closeQuietly(instream);

                assertEquals(baos2.toByteArray(), sourceBytes);

                if (!deflate.get(i)) {

                    String helloString = new String(baos2.toByteArray());
                    assertEquals(helloString, expectedContents.get(i));
                } else {
                    instream = new FileInputStream(tmp);

                    ByteArrayOutputStream baos3 = new ByteArrayOutputStream();
                    GZIPInputStream deflater = new GZIPInputStream(instream);
                    try {
                        byte[] buf3 = new byte[8092];
                        int len3 = 0;
                        while ((len3 = deflater.read(buf3)) > 0) {
                            baos3.write(buf3, 0, len3);
                        }
                    } finally {
                        deflater.close();
                    }

                    String helloString = new String(baos3.toByteArray());

                    assertEquals(expectedContents.get(i), helloString);

                }
            } catch (Exception e) {
                e.printStackTrace();
                fail("Download Exception");
            } finally {
                if (tmp != null)
                    FileUtils.deleteQuietly(tmp);
                IOUtils.closeQuietly(instream);
                i++;
            }
        }
    }

    /**
     * Takes the content that is being passed to it, and streams to a file on disk
     * 
     * @author dominict
     */
    public static class MockMultipartUploadServlet extends HttpServlet {

        private static final Logger LOGGER = LoggerFactory.getLogger(MockMultipartUploadServlet.class);

        private static final long serialVersionUID = 1L;
        private int filesProcessed = 0;
        private int stringsProcessed = 0;

        public MockMultipartUploadServlet() {

        }

        public synchronized void resetFilesProcessed() {
            filesProcessed = 0;
        }

        private synchronized int incrementFilesProcessed() {
            return ++filesProcessed;

        }

        public int getFilesProcessed() {
            return filesProcessed;
        }

        public synchronized void resetStringsProcessed() {
            stringsProcessed = 0;
        }

        private synchronized int incrementStringsProcessed() {
            return ++stringsProcessed;

        }

        public int getStringsProcessed() {
            return stringsProcessed;
        }

        @Override
        public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            // Check that we have a file upload request
            boolean isMultipart = ServletFileUpload.isMultipartContent(request);
            if (isMultipart) {
                List<String> files = new ArrayList<String>();
                ServletFileUpload upload = new ServletFileUpload();
                // Parse the request
                FileItemIterator iter = null;
                try {
                    iter = upload.getItemIterator(request);
                    while (iter.hasNext()) {
                        FileItemStream item = iter.next();
                        String name = item.getFieldName();
                        InputStream stream = null;
                        try {
                            stream = item.openStream();

                            if (item.isFormField()) {
                                LOGGER.debug("Form field " + name + " with value " + Streams.asString(stream) + " detected.");
                                incrementStringsProcessed();
                            } else {
                                LOGGER.debug("File field " + name + " with file name " + item.getName() + " detected.");
                                // Process the input stream
                                OutputStream os = null;
                                try {
                                    File tmpFile = File.createTempFile(UUID.randomUUID().toString() + "_MockUploadServlet", ".tmp");
                                    tmpFile.deleteOnExit();
                                    os = new FileOutputStream(tmpFile);
                                    byte[] buffer = new byte[4096];
                                    int bytesRead;
                                    while ((bytesRead = stream.read(buffer)) != -1) {
                                        os.write(buffer, 0, bytesRead);
                                    }
                                    incrementFilesProcessed();
                                    files.add(tmpFile.getAbsolutePath());
                                } finally {
                                    IOUtils.closeQuietly(os);
                                }
                            }
                        } finally {
                            IOUtils.closeQuietly(stream);
                        }
                    }
                } catch (FileUploadException e) {

                }
                Writer w = response.getWriter();
                try {
                    w.write(Integer.toString(getFilesProcessed()));
                    resetFilesProcessed();
                    resetStringsProcessed();
                    w.write("||");
                    w.write(files.toString());
                } finally {
                    // FIXME
                    w.close();
                }
            } else {
                Writer w = response.getWriter();
                try {
                    w.write(Integer.toString(getFilesProcessed()));
                    resetFilesProcessed();
                    resetStringsProcessed();
                    w.write("||");
                } finally {
                    w.close();
                }
            }
        }
    }
}
