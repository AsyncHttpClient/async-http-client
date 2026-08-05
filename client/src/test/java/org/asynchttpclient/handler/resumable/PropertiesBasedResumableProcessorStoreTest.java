/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.handler.resumable;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Covers how the resumable index store is created in the shared temp directory. The store sits at a fixed,
 * predictable path that every local user can write to, so it must be created owner-only and must never be
 * written or read through something another user planted there.
 */
public class PropertiesBasedResumableProcessorStoreTest {

  private static final Path STORE = Paths.get(System.getProperty("java.io.tmpdir"), "ahc", "ResumableAsyncHandler.properties");

  private static void requirePosix() {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      throw new SkipException("POSIX file permissions are not supported on this file system");
    }
  }

  private static void deleteStore() throws IOException {
    Files.deleteIfExists(STORE);
  }

  @Test
  public void storeIsCreatedOwnerOnly() throws IOException {
    requirePosix();
    deleteStore();

    PropertiesBasedResumableProcessor processor = new PropertiesBasedResumableProcessor();
    processor.put("http://localhost/owner-only.url", 15L);
    processor.save(null);

    assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(STORE)), "rw-------",
            "the store must be created owner-readable/writable only");
  }

  @Test
  public void saveDoesNotWriteThroughASymlink() throws IOException {
    requirePosix();
    deleteStore();
    Files.createDirectories(STORE.getParent());

    Path target = Files.createTempFile("ahc-symlink-target", ".txt");
    try {
      Files.write(target, "untouched".getBytes(StandardCharsets.UTF_8));
      Files.createSymbolicLink(STORE, target);

      PropertiesBasedResumableProcessor processor = new PropertiesBasedResumableProcessor();
      processor.put("http://localhost/symlink.url", 15L);
      processor.save(null);

      assertEquals(new String(Files.readAllBytes(target), StandardCharsets.UTF_8), "untouched",
              "a symlink planted at the store path must not be followed and its target must not be truncated");
    } finally {
      Files.deleteIfExists(target);
      deleteStore();
    }
  }

  @Test
  public void loadDoesNotReadThroughASymlink() throws IOException {
    requirePosix();
    deleteStore();
    Files.createDirectories(STORE.getParent());

    Path target = Files.createTempFile("ahc-symlink-source", ".txt");
    try {
      Files.write(target, "http://localhost/planted.url=42\n".getBytes(StandardCharsets.UTF_8));
      Files.createSymbolicLink(STORE, target);

      Map<String, Long> loaded = new PropertiesBasedResumableProcessor().load();

      assertTrue(loaded.isEmpty(), "state must not be read back through a planted symlink, got " + loaded);
    } finally {
      Files.deleteIfExists(target);
      deleteStore();
    }
  }
}
