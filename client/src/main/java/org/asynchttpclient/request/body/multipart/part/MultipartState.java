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

/**
 * Represents the state of a multipart part during transfer.
 * <p>
 * This enum tracks the progress of transferring a multipart part, which consists of
 * three phases: pre-content (headers and boundary), content (actual data), and
 * post-content (trailing boundary). The DONE state indicates the part has been
 * completely transferred.
 * </p>
 */
public enum MultipartState {

  /**
   * The part is transferring pre-content data (boundary, headers).
   * This is the initial state when a part begins transfer.
   */
  PRE_CONTENT,

  /**
   * The part is transferring the actual content data.
   * This state follows PRE_CONTENT once all headers have been written.
   */
  CONTENT,

  /**
   * The part is transferring post-content data (trailing CRLF).
   * This state follows CONTENT once all data has been written.
   */
  POST_CONTENT,

  /**
   * The part transfer is complete.
   * All data for this part has been successfully transferred.
   */
  DONE
}
