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
package org.asynchttpclient.request.body.multipart;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.Streams;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.getClasspathFile;
import static org.testng.Assert.*;

/**
 * @author dominict
 */
public class MultipartUploadTest extends AbstractBasicTest {

  @BeforeClass
  public void setUp() throws Exception {
    server = new Server();
    ServerConnector connector = addHttpConnector(server);
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.addServlet(new ServletHolder(new MockMultipartUploadServlet()), "/upload");
    server.setHandler(context);
    server.start();
    port1 = connector.getLocalPort();
  }

  @Test
  public void testSendingSmallFilesAndByteArray() throws Exception {
    String expectedContents = "filecontent: hello";
    String expectedContents2 = "gzipcontent: hello";
    String expectedContents3 = "filecontent: hello2";
    String testResource1 = "textfile.txt";
    String testResource2 = "gzip.txt.gz";
    String testResource3 = "textfile2.txt";

    File testResource1File = getClasspathFile(testResource1);
    File testResource2File = getClasspathFile(testResource2);
    File testResource3File = getClasspathFile(testResource3);

    List<File> testFiles = new ArrayList<>();
    testFiles.add(testResource1File);
    testFiles.add(testResource2File);
    testFiles.add(testResource3File);

    List<String> expected = new ArrayList<>();
    expected.add(expectedContents);
    expected.add(expectedContents2);
    expected.add(expectedContents3);

    List<Boolean> gzipped = new ArrayList<>();
    gzipped.add(false);
    gzipped.add(true);
    gzipped.add(false);

    File tmpFile = File.createTempFile("textbytearray", ".txt");
    try (OutputStream os = Files.newOutputStream(tmpFile.toPath())) {
      IOUtils.write(expectedContents.getBytes(UTF_8), os);

      testFiles.add(tmpFile);
      expected.add(expectedContents);
      gzipped.add(false);
    }

    try (AsyncHttpClient c = asyncHttpClient(config())) {
      Request r = post("http://localhost" + ":" + port1 + "/upload")
              .addBodyPart(new FilePart("file1", testResource1File, "text/plain", UTF_8))
              .addBodyPart(new FilePart("file2", testResource2File, "application/x-gzip", null))
              .addBodyPart(new StringPart("Name", "Dominic"))
              .addBodyPart(new FilePart("file3", testResource3File, "text/plain", UTF_8))
              .addBodyPart(new StringPart("Age", "3")).addBodyPart(new StringPart("Height", "shrimplike"))
              .addBodyPart(new StringPart("Hair", "ridiculous")).addBodyPart(new ByteArrayPart("file4",
                      expectedContents.getBytes(UTF_8), "text/plain", UTF_8, "bytearray.txt"))
              .build();

      Response res = c.executeRequest(r).get();

      assertEquals(res.getStatusCode(), 200);

      testSentFile(expected, testFiles, res, gzipped);
    }
  }

  private void sendEmptyFile0(boolean disableZeroCopy) throws Exception {
    File file = getClasspathFile("empty.txt");
    try (AsyncHttpClient c = asyncHttpClient(config().setDisableZeroCopy(disableZeroCopy))) {
      Request r = post("http://localhost" + ":" + port1 + "/upload")
              .addBodyPart(new FilePart("file", file, "text/plain", UTF_8)).build();

      Response res = c.executeRequest(r).get();
      assertEquals(res.getStatusCode(), 200);
    }
  }

  @Test
  public void sendEmptyFile() throws Exception {
    sendEmptyFile0(true);
  }

  @Test
  public void sendEmptyFileZeroCopy() throws Exception {
    sendEmptyFile0(false);
  }

  /**
   * Test that the files were sent, based on the response from the servlet
   */
  private void testSentFile(List<String> expectedContents, List<File> sourceFiles, Response r,
                            List<Boolean> deflate) {
    String content = r.getResponseBody();
    assertNotNull(content);
    logger.debug(content);

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

      File tmp = null;
      try {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] sourceBytes;
        try (InputStream instream = Files.newInputStream(sourceFile.toPath())) {
          byte[] buf = new byte[8092];
          int len;
          while ((len = instream.read(buf)) > 0) {
            baos.write(buf, 0, len);
          }
          logger.debug("================");
          logger.debug("Length of file: " + baos.toByteArray().length);
          logger.debug("Contents: " + Arrays.toString(baos.toByteArray()));
          logger.debug("================");
          System.out.flush();
          sourceBytes = baos.toByteArray();
        }

        tmp = new File(responseFiles[i].trim());
        logger.debug("==============================");
        logger.debug(tmp.getAbsolutePath());
        logger.debug("==============================");
        System.out.flush();
        assertTrue(tmp.exists());

        byte[] bytes;
        try (InputStream instream = Files.newInputStream(tmp.toPath())) {
          ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
          byte[] buf = new byte[8092];
          int len;
          while ((len = instream.read(buf)) > 0) {
            baos2.write(buf, 0, len);
          }
          bytes = baos2.toByteArray();
          assertEquals(bytes, sourceBytes);
        }

        if (!deflate.get(i)) {
          String helloString = new String(bytes);
          assertEquals(helloString, expectedContents.get(i));
        } else {
          try (InputStream instream = Files.newInputStream(tmp.toPath())) {
            ByteArrayOutputStream baos3 = new ByteArrayOutputStream();
            GZIPInputStream deflater = new GZIPInputStream(instream);
            try {
              byte[] buf3 = new byte[8092];
              int len3;
              while ((len3 = deflater.read(buf3)) > 0) {
                baos3.write(buf3, 0, len3);
              }
            } finally {
              deflater.close();
            }

            String helloString = new String(baos3.toByteArray());

            assertEquals(expectedContents.get(i), helloString);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
        fail("Download Exception");
      } finally {
        if (tmp != null)
          FileUtils.deleteQuietly(tmp);
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

    MockMultipartUploadServlet() {

    }

    synchronized void resetFilesProcessed() {
      filesProcessed = 0;
    }

    private synchronized int incrementFilesProcessed() {
      return ++filesProcessed;
    }

    int getFilesProcessed() {
      return filesProcessed;
    }

    synchronized void resetStringsProcessed() {
      stringsProcessed = 0;
    }

    private synchronized int incrementStringsProcessed() {
      return ++stringsProcessed;

    }

    public int getStringsProcessed() {
      return stringsProcessed;
    }

    @Override
    public void service(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
      // Check that we have a file upload request
      boolean isMultipart = ServletFileUpload.isMultipartContent(request);
      if (isMultipart) {
        List<String> files = new ArrayList<>();
        ServletFileUpload upload = new ServletFileUpload();
        // Parse the request
        FileItemIterator iter;
        try {
          iter = upload.getItemIterator(request);
          while (iter.hasNext()) {
            FileItemStream item = iter.next();
            String name = item.getFieldName();
            try (InputStream stream = item.openStream()) {

              if (item.isFormField()) {
                LOGGER.debug("Form field " + name + " with value " + Streams.asString(stream)
                        + " detected.");
                incrementStringsProcessed();
              } else {
                LOGGER.debug("File field " + name + " with file name " + item.getName() + " detected.");
                // Process the input stream
                File tmpFile = File.createTempFile(UUID.randomUUID().toString() + "_MockUploadServlet",
                        ".tmp");
                tmpFile.deleteOnExit();
                try (OutputStream os = Files.newOutputStream(tmpFile.toPath())) {
                  byte[] buffer = new byte[4096];
                  int bytesRead;
                  while ((bytesRead = stream.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                  }
                  incrementFilesProcessed();
                  files.add(tmpFile.getAbsolutePath());
                }
              }
            }
          }
        } catch (FileUploadException e) {
          //
        }
        try (Writer w = response.getWriter()) {
          w.write(Integer.toString(getFilesProcessed()));
          resetFilesProcessed();
          resetStringsProcessed();
          w.write("||");
          w.write(files.toString());
        }
      } else {
        try (Writer w = response.getWriter()) {
          w.write(Integer.toString(getFilesProcessed()));
          resetFilesProcessed();
          resetStringsProcessed();
          w.write("||");
        }
      }
    }
  }
}
