/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.retrofit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import rx.Observable;

import java.io.Serializable;
import java.util.List;

/**
 * Github DTOs and services.
 */
class TestServices {
  /**
   * Synchronous interface
   */
  public interface GithubSync {
    @GET("/repos/{owner}/{repo}/contributors")
    Call<List<Contributor>> contributors(@Path("owner") String owner, @Path("repo") String repo);
  }

  /**
   * RxJava 1.x reactive interface
   */
  public interface GithubRxJava1 {
    @GET("/repos/{owner}/{repo}/contributors")
    Observable<List<Contributor>> contributors(@Path("owner") String owner, @Path("repo") String repo);
  }

  /**
   * RxJava 2.x reactive interface
   */
  public interface GithubRxJava2 {
    @GET("/repos/{owner}/{repo}/contributors")
    io.reactivex.Single<List<Contributor>> contributors(@Path("owner") String owner, @Path("repo") String repo);
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Contributor implements Serializable {
    private static final long serialVersionUID = 1;

    @NonNull
    String login;

    int contributions;
  }
}
