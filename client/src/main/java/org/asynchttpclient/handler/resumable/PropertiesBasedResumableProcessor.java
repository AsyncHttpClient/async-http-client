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
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.asynchttpclient.util.MiscUtils.closeSilently;

/**
 * A {@link org.asynchttpclient.handler.resumable.ResumableAsyncHandler.ResumableProcessor} which use a properties file
 * to store the download index information.
 */
public class PropertiesBasedResumableProcessor implements ResumableAsyncHandler.ResumableProcessor {
  private final static Logger log = LoggerFactory.getLogger(PropertiesBasedResumableProcessor.class);
  private final static File TMP = new File(System.getProperty("java.io.tmpdir"), "ahc");
  private final static String storeName = "ResumableAsyncHandler.properties";
  private final static boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  private final static FileAttribute<?>[] DIR_ATTRIBUTES = ownerOnlyAttributes("rwx------");
  private final static FileAttribute<?>[] FILE_ATTRIBUTES = ownerOnlyAttributes("rw-------");
  private final static Set<StandardOpenOption> CREATE_OPTIONS = EnumSet.of(WRITE, CREATE_NEW);

  private final ConcurrentHashMap<String, Long> properties = new ConcurrentHashMap<>();

  private static FileAttribute<?>[] ownerOnlyAttributes(String permissions) {
    return POSIX
            ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))}
            : new FileAttribute<?>[0];
  }

  private static String append(Map.Entry<String, Long> e) {
    return e.getKey() + '=' + e.getValue() + '\n';
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void put(String url, long transferredBytes) {
    properties.put(url, transferredBytes);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void remove(String uri) {
    if (uri != null) {
      properties.remove(uri);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void save(Map<String, Long> map) {
    log.debug("Saving current download state {}", properties.toString());
    OutputStream os = null;
    try {

      Path dir = TMP.toPath();
      if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectory(dir, DIR_ATTRIBUTES);
      }

      // The store sits at a fixed path in the shared temp directory and holds the URLs being downloaded,
      // so it is recreated here with owner-only permissions instead of being written through whatever is
      // already at that path. CREATE_NEW after the delete fails rather than opens if another local user
      // re-plants a file or a symlink in between.
      Path f = dir.resolve(storeName);
      Files.deleteIfExists(f);
      os = Channels.newOutputStream(Files.newByteChannel(f, CREATE_OPTIONS, FILE_ATTRIBUTES));
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
   * {@inheritDoc}
   */
  @Override
  public Map<String, Long> load() {
    Scanner scan = null;
    try {
      // NOFOLLOW_LINKS: refuse to read the state back through a symlink planted at the predictable path
      scan = new Scanner(Files.newInputStream(new File(TMP, storeName).toPath(), LinkOption.NOFOLLOW_LINKS), UTF_8.name());
      scan.useDelimiter("[=\n]");

      String key;
      String value;
      while (scan.hasNext()) {
        key = scan.next().trim();
        value = scan.next().trim();
        properties.put(key, Long.valueOf(value));
      }
      log.debug("Loading previous download state {}", properties.toString());
    } catch (NoSuchFileException ex) {
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
