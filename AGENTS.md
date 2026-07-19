# AsyncHttpClient - AI Agent Guidelines

Guidelines for AI agents contributing to this repository.

## Project Information

* Java baseline is JDK 11. All code must compile and run on JDK 11. Source and target are defined in the root `pom.xml`.
* CI runs on Amazon Corretto JDK 11, 17, 21 and 25 across Linux, macOS and Windows.
* Maven MUST always be invoked through the Maven Wrapper. Use `./mvnw` on Linux and macOS and `mvnw.cmd` on Windows. Do not use a system Maven installation.

## AI Agent Requirements

These requirements apply to all AI agents working on this repository.

### Attribution

* All AI-generated commits and pull requests MUST clearly identify the AI agent and the human operator.
* Use the format `<AI Agent> on behalf of <Human Name>`.
* Example: `Claude Code on behalf of Alice Smith`.
* Every AI-generated commit MUST include a `Co-Authored-By` trailer for the AI agent.

### Git

* Work in topic branches with descriptive names. Never commit directly to `main`.
* Never force-push shared branches.
* Never rewrite repository history unless explicitly instructed.
* Never start a commit message with `[maven-release-plugin]`. The release workflow treats that prefix as release automation.

### Issues and Pull Requests

* Use GitHub Issues for bugs and feature requests. Use GitHub Discussions for questions.
* Before implementing a bug fix:
  * Reproduce the issue.
  * Inspect `git log` and `git blame` for the affected files.
  * Search existing issues and pull requests.
  * Confirm the change does not revert earlier intentional work.
* Keep pull requests small and focused. One logical change per pull request.
* Do not combine unrelated fixes, refactorings or formatting changes.

### Testing Gate - Hard Requirement

Before pushing any commit or opening a pull request, run:

```sh
./mvnw clean verify
```

Requirements:

* The command MUST be run on JDK 11.
* The build MUST complete successfully.
* Do not disable, skip or weaken tests to obtain a passing build.

## Build

Common commands:

```sh
# Full verification
./mvnw clean verify

# Compile and API compatibility check only
./mvnw -B -ntp clean verify -DskipTests -Dgpg.skip=true

# Run a single test class
./mvnw test -Dtest=BasicHttpTest

# Run a single test method
./mvnw test -Dtest=BasicHttpTest#testMethodName
```

### Build Notes

* Error Prone and NullAway run during compilation. Nullability violations in production code under `org.asynchttpclient` fail the build.
* Respect the JetBrains `@Nullable` and `@NotNull` annotations. Test sources are excluded from NullAway checks.
* Revapi runs during the `verify` phase and compares the public API against the latest release on Maven Central. Treat API compatibility failures as intentional feedback, not build noise.

## Repository Layout

* `pom.xml` - parent and aggregator POM
* `client`

  * `src/main/java/org/asynchttpclient` - production sources
  * `src/test/java` - test sources
  * `src/jmh/java` - JMH benchmarks
* `docs` - design documentation
* `.github/workflows` - CI workflows

## Testing

* Use JUnit 5 only.
* Test classes must end with the `Test` suffix and mirror the production package structure.
* Extend `AbstractBasicTest` for tests requiring an embedded Jetty server.
* Extend `AbstractBasicWebSocketTest` for WebSocket tests.
* Never use `Thread.sleep()` for synchronization. Use futures, latches or timeouts.
* Mark known flaky tests with `@RepeatedIfExceptionsTest` instead of `@Test`.
* Do not leak Netty `ByteBuf` instances. The leak detector extension will fail the test.
* Keep the default test suite hermetic.
* Tests requiring public hosts must be tagged `external`.
* Docker-based integration tests must follow the existing Testcontainers gating properties.

## Coding Conventions

* Use four spaces for indentation. Never use tabs.
* Files are UTF-8 encoded, but repository content MUST use ASCII characters only.
* Do not use wildcard imports.
* Preserve the surrounding coding style.
* Keep diffs as small as practical.
* Avoid unrelated formatting changes.

Every new Java file MUST begin with the repository license header:

```java
/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
```

## Comments

* Keep comments minimal.
* Add comments only to explain constraints, invariants or non-obvious reasoning that the code itself cannot express.
* Match the comment density of the surrounding file.
* Do not add comments describing a code change or addressing reviewers.

## Commit Messages

Commit messages MUST contain:

* An imperative summary line of at most 50 characters.
* A blank line.
* A body wrapped at 72 characters explaining why the change was made, not only what changed.
* A reference to the GitHub issue when applicable.
* The required AI attribution trailer.

## API Compatibility

* Public API changes MUST be intentional.
* Do not introduce, remove or modify public API unless it is the explicit goal of the change.
* Treat Revapi failures as design feedback rather than something to work around.

## Security

Treat all network input as hostile.

Pay particular attention to:

* URI parsing and normalization.
* Header values and CRLF injection.
* Request smuggling.
* Credential and `Realm` handling, especially across redirects and origins.
* Proxy and CONNECT handling.
* TLS configuration and hostname verification.
* Decompression limits and memory usage.
* Cookie parsing and storage.

## Knowledge Verification

* Do not make factual claims about external projects or dependency versions from memory.
* Verify dependency information against the repository and Maven Central before making assertions.
* When uncertain, state that uncertainty instead of guessing.

## Links

* `https://github.com/AsyncHttpClient/async-http-client`
* `https://github.com/AsyncHttpClient/async-http-client/issues`
* `https://github.com/AsyncHttpClient/async-http-client/discussions`
