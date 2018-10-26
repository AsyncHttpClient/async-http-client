package org.asynchttpclient.request.body.multipart;

import java.io.InputStream;
import java.nio.charset.Charset;

import static org.asynchttpclient.util.Assertions.assertNotNull;

public class InputStreamPart extends FileLikePart {

  private final InputStream inputStream;
  private final long contentLength;

  public InputStreamPart(String name, InputStream inputStream, long contentLength, String fileName) {
    this(name, inputStream, contentLength, fileName, null);
  }

  public InputStreamPart(String name, InputStream inputStream, long contentLength, String fileName, String contentType) {
    this(name, inputStream, contentLength, fileName, contentType, null);
  }

  public InputStreamPart(String name, InputStream inputStream, long contentLength, String fileName, String contentType, Charset charset) {
    this(name, inputStream, contentLength, fileName, contentType, charset, null);
  }

  public InputStreamPart(String name, InputStream inputStream, long contentLength, String fileName, String contentType, Charset charset,
                         String contentId) {
    this(name, inputStream, contentLength, fileName, contentType, charset, contentId, null);
  }

  public InputStreamPart(String name, InputStream inputStream, long contentLength, String fileName, String contentType, Charset charset,
                         String contentId, String transferEncoding) {
    super(name,
            contentType,
            charset,
            fileName,
            contentId,
            transferEncoding);
    this.inputStream = assertNotNull(inputStream, "inputStream");
    this.contentLength = contentLength;
  }

  public InputStream getInputStream() {
    return inputStream;
  }

  public long getContentLength() {
    return contentLength;
  }
}
