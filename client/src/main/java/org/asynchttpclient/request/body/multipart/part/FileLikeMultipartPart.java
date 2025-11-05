package org.asynchttpclient.request.body.multipart.part;

import org.asynchttpclient.request.body.multipart.FileLikePart;

import static java.nio.charset.StandardCharsets.*;

/**
 * Abstract base class for multipart parts representing file-like content.
 * <p>
 * This class extends {@link MultipartPart} to provide common functionality for parts
 * that have file names, such as file uploads, byte arrays, and input streams. It adds
 * the filename parameter to the Content-Disposition header when present.
 * </p>
 * <p>
 * Concrete implementations include {@link FileMultipartPart}, {@link ByteArrayMultipartPart},
 * and {@link InputStreamMultipartPart}.
 * </p>
 *
 * @param <T> the type of FileLikePart this multipart part represents
 */
public abstract class FileLikeMultipartPart<T extends FileLikePart> extends MultipartPart<T> {

  /**
   * The filename parameter prefix for the Content-Disposition header.
   */
  private static final byte[] FILE_NAME_BYTES = "; filename=".getBytes(US_ASCII);

  /**
   * Constructs a file-like multipart part.
   *
   * @param part     the file-like part containing metadata
   * @param boundary the multipart boundary bytes
   */
  FileLikeMultipartPart(T part, byte[] boundary) {
    super(part, boundary);
  }

  protected void visitDispositionHeader(PartVisitor visitor) {
    super.visitDispositionHeader(visitor);
    if (part.getFileName() != null) {
      visitor.withBytes(FILE_NAME_BYTES);
      visitor.withByte(QUOTE_BYTE);
      visitor.withBytes(part.getFileName().getBytes(part.getCharset() != null ? part.getCharset() : UTF_8));
      visitor.withByte(QUOTE_BYTE);
    }
  }
}
