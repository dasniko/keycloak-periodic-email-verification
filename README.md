# Keycloak Periodic Email Verification

## General

These custom extensions are based on
![](https://img.shields.io/badge/Keycloak-26.8-blue)
![](https://img.shields.io/badge/Java-21-f89820)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE.md)

### Building the Binaries

You can build the binaries if you have a local installation of JDK, with

    $ ./mvnw clean package

If you don't have local JDK installation, but Docker is available, you can use the helper script

    $ ./build-with-docker.sh

Both approaches will build the binary `target/periodic-email-verification-<version>.jar`
and additionally copy it to the directory `target/providers/`.

`./mvnw clean package` also runs the tests, which require a running Docker environment (see [Running the Tests](#running-the-tests)).
Without Docker, skip them with `./mvnw clean package -DskipTests`.
The helper script `build-with-docker.sh` builds with `-DskipTests`.

### Running the Tests

The integration tests are based on [Testcontainers](https://testcontainers.com/) and run the extension in a real Keycloak container.
A Mailpit container catches the emails sent by Keycloak.

**Prerequisite:** a running Docker environment, which is accessible for the user running Maven.
The images `quay.io/keycloak/keycloak:<keycloak.version from pom.xml>` and `axllent/mailpit:latest` are pulled, if not yet available locally.

    $ ./mvnw test

The Keycloak and Mailpit containers are started for each test class, so a test run takes some time.

### Local Development with Docker Compose

The `docker-compose.yml` starts Keycloak (with the test realm imported) and a Mailpit instance (UI: http://localhost:8025).
The directory `target/providers/` is mounted into the Keycloak container as `/opt/keycloak/providers`,
so any JAR built there is picked up on container start.

    $ ./deploy.sh

which is the same as `mvn clean package -DskipTests && docker compose up`.

Keycloak loads providers on startup only, so restart the container after each rebuild.
Note that `mvn clean` removes `target/providers/`, so rebuild while the container is stopped.

### Download the Binaries

Alternatively to building the binaries yourself, you can also download them from GitHub actions.

Go to [Actions](https://github.com/dasniko/keycloak-periodic-email-verification/actions/workflows/maven.yml) and click on the most recent workflow run.

In the details of this execution, you can find the section `Artifacts` with the artifact named `<repository-name>-<short-commit-sha>` listed.
It contains the provider JAR (`periodic-email-verification-<version>.jar`).
Here you can download the artifact up until 90 days after the last build time.

### Deployment

Deploy the built JAR file into the `$KEYCLOAK_HOME/providers` folder of your Keycloak server (or copy it to your Docker image).

When running Keycloak in `optimized` mode, you'll have to run a `build` after deploying the JAR.
If you start Keycloak regularly, an implicit build will be performed.
See [Keycloak Docs](https://www.keycloak.org/docs) and [Keycloak Guides](https://www.keycloak.org/guides) for more info.

## Extensions

### Periodic Verify Email Action

The `PeriodicVerifyEmail` action extends and automatically overloads the built-in required action `VERIFY_EMAIL`.

It tests periodically if a user has to re-verify its email address.
To configure the duration how often a user has to re-verify the email, see here:

![](doc/periodic-verify-email.gif)

### Extended Verify Email Action Token Handler

The `ExtendedVerifyEmailActionTokenHandler` extends and automatically overloads the built-in action token handler.

It adds setting the `emailVerifiedAt` attribute to the current timestamp when the user confirms/verifies its email via the action token link.

## Limitations

The periodic verification is evaluated only when a user authenticates, i.e. as part of the login.
Existing sessions, as well as refresh and offline tokens, are not checked again by Keycloak.
A user with a long-living session or token can therefore stay signed in beyond the configured verification period, without being asked to verify the email address.

Choose the session lifetimes (e.g. SSO session max, offline session settings) to match the configured verification period.

## License

Copyright 2025-2026 Niko Köbler

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

The full license text is available in [LICENSE.md](LICENSE.md).

Small parts of the code mirror logic of [Keycloak](https://github.com/keycloak/keycloak), which is licensed under the Apache License, Version 2.0 as well.
Such places are marked with the `@CopiedFromKeycloak` annotation.
