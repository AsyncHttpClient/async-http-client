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
package org.asynchttpclient.handler.resumable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.util.MiscUtils.closeSilently;

/**
 * A {@link ResumableAsyncHandler.ResumableProcessor} implementation that persists download progress
 * to a properties file in the system temporary directory.
 * <p>
 * This processor stores URL-to-byte-position mappings in a properties file, enabling
 * downloads to resume after application restarts or crashes. The properties file is
 * stored at {@code ${java.io.tmpdir}/ahc/ResumableAsyncHandler.properties}.
 * <p>
 * The processor maintains an in-memory cache of the download state and persists it to
 * disk when {@link #save(Map)} is called (typically during JVM shutdown via a shutdown hook).
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * PropertiesBasedResumableProcessor processor = new PropertiesBasedResumableProcessor();
 * ResumableAsyncHandler handler = new ResumableAsyncHandler(processor);
 *
 * // The processor automatically loads previous download state
 * client.prepareGet("http://example.com/largefile.zip")
 *     .execute(handler)
 *     .get();
 *
 * // Download progress is automatically saved on JVM shutdown
 * }</pre>
 */
public class PropertiesBasedResumableProcessor implements ResumableAsyncHandler.ResumableProcessor {
  private final static Logger log = LoggerFactory.getLogger(PropertiesBasedResumableProcessor.class);
  private final static File TMP = new File(System.getProperty("java.io.tmpdir"), "ahc");
  private final static String storeName = "ResumableAsyncHandler.properties";
  private final ConcurrentHashMap<String, Long> properties = new ConcurrentHashMap<>();

  private static String append(Map.Entry<String, Long> e) {
    return e.getKey() + '=' + e.getValue() + '\n';
  }

  /**
   * Stores the number of bytes transferred for the specified URL.
   * <p>
   * Updates the in-memory cache with the current download position. This information
   * will be persisted to disk when {@link #save(Map)} is called.
   *
   * @param url the URL being downloaded (used as the key)
   * @param transferredBytes the number of bytes successfully transferred so far
   */
  @Override
  public void put(String url, long transferredBytes) {
    properties.put(url, transferredBytes);
  }

  /**
   * Removes the download state for the specified URL.
   * <p>
   * This is typically called when a download completes successfully, removing the
   * entry from both the in-memory cache and (on next save) the persistent storage.
   *
   * @param uri the URL whose download state should be removed
   */
  @Override
  public void remove(String uri) {
    if (uri != null) {
      properties.remove(uri);
    }
  }

  /**
   * Persists the download state to a properties file.
   * <p>
   * This method is typically invoked during JVM shutdown via a shutdown hook registered
   * by {@link ResumableAsyncHandler}. It writes all URL-to-byte-position mappings to
   * {@code ${java.io.tmpdir}/ahc/ResumableAsyncHandler.properties}.
   * <p>
   * The method creates the necessary directories if they don't exist and handles
   * errors gracefully by logging warnings rather than throwing exceptions.
   *
   * @param map the complete download state map (not used; this implementation uses internal state)
   */
  @Override
  public void save(Map<String, Long> map) {
    log.debug("Saving current download state {}", properties.toString());
    OutputStream os = null;
    try {

      if (!TMP.exists() && !TMP.mkdirs()) {
        throw new IllegalStateException("Unable to create directory: " + TMP.getAbsolutePath());
      }
      File f = new File(TMP, storeName);
      if (!f.exists() && !f.createNewFile()) {
        throw new IllegalStateException("Unable to create temp file: " + f.getAbsolutePath());
      }
      if (!f.canWrite()) {
        throw new IllegalStateException();
      }

      os = Files.newOutputStream(f.toPath());
      for (Map.Entry<String, Long> e : properties.entrySet()) {
        os.write(append(e).getBytes(UTF_8));
      }
      os.flush();
    } catch (Throwable e) {
      log.warn(e.getMessage(), e);
    } finally {
      closeSilently(os);
    }
  }

  /**
   * Loads the previously saved download state from the properties file.
   * <p>
   * This method is called when a {@link ResumableAsyncHandler} is created. It reads
   * the properties file from {@code ${java.io.tmpdir}/ahc/ResumableAsyncHandler.properties}
   * and loads all URL-to-byte-position mappings into memory.
   * <p>
   * If the file doesn't exist or cannot be read, the method returns an empty map
   * and logs appropriate messages.
   *
   * @return a map containing URL-to-byte-position mappings from the previous session
   */
  @Override
  public Map<String, Long> load() {
    Scanner scan = null;
    try {
      scan = new Scanner(new File(TMP, storeName), UTF_8.name());
      scan.useDelimiter("[=\n]");

      String key;
      String value;
      while (scan.hasNext()) {
        key = scan.next().trim();
        value = scan.next().trim();
        properties.put(key, Long.valueOf(value));
      }
      log.debug("Loading previous download state {}", properties.toString());
    } catch (FileNotFoundException ex) {
      log.debug("Missing {}", storeName);
    } catch (Throwable ex) {
      // Survive any exceptions
      log.warn(ex.getMessage(), ex);
    } finally {
      if (scan != null)
        scan.close();
    }
    return properties;
  }
}
