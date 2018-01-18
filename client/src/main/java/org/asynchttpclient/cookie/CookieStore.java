/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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

package org.asynchttpclient.cookie;

import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.uri.Uri;

import java.net.CookieManager;
import java.util.List;
import java.util.function.Predicate;

/**
 * This interface represents an abstract store for {@link Cookie} objects.
 *
 * <p>{@link CookieManager} will call {@code CookieStore.add} to save cookies
 * for every incoming HTTP response, and call {@code CookieStore.get} to
 * retrieve cookie for every outgoing HTTP request. A CookieStore
 * is responsible for removing HttpCookie instances which have expired.
 *
 * @since 2.1
 */
public interface CookieStore {
  /**
   * Adds one {@link Cookie} to the store. This is called for every incoming HTTP response.
   * If the given cookie has already expired it will not be added, but existing values will still be removed.
   *
   * <p>A cookie to store may or may not be associated with an URI. If it
   * is not associated with an URI, the cookie's domain and path attribute
   * will indicate where it comes from. If it is associated with an URI and
   * its domain and path attribute are not specified, given URI will indicate
   * where this cookie comes from.
   *
   * <p>If a cookie corresponding to the given URI already exists,
   * then it is replaced with the new one.
   *
   * @param uri    the {@link Uri uri} this cookie associated with. if {@code null}, this cookie will not be associated with an URI
   * @param cookie the {@link Cookie cookie} to be added
   */
  void add(Uri uri, Cookie cookie);

  /**
   * Retrieve cookies associated with given URI, or whose domain matches the given URI. Only cookies that
   * have not expired are returned. This is called for every outgoing HTTP request.
   *
   * @param uri the {@link Uri uri} associated with the cookies to be returned
   * @return an immutable list of Cookie, return empty list if no cookies match the given URI
   */
  List<Cookie> get(Uri uri);

  /**
   * Get all not-expired cookies in cookie store.
   *
   * @return an immutable list of http cookies;
   * return empty list if there's no http cookie in store
   */
  List<Cookie> getAll();

  /**
   * Remove a cookie from store.
   *
   * @param predicate that indicates what cookies to remove
   * @return {@code true} if this store contained the specified cookie
   * @throws NullPointerException if {@code cookie} is {@code null}
   */
  boolean remove(Predicate<Cookie> predicate);

  /**
   * Remove all cookies in this cookie store.
   *
   * @return true if any cookies were purged.
   */
  boolean clear();
}
