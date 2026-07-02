# SPDX-License-Identifier: Apache-2.0
#
# Community-plus-ISVA image. Ships under Apache 2.0 with core/ + addons/isva
# only — no ee/ bytecode. The verify-no-ee-bytecode antrun check in
# distribution/community-plus-isva/pom.xml fails the Maven build if anything
# ee leaks into this JAR, so the artifact the runtime stage copies is already
# license-clean.
#
# Multi-stage: the JAR is compiled in the `build` stage below, so a CI runner
# needs only Docker/Kaniko — no JDK or Maven toolchain (see
# .github/workflows/ghcr-publish.yml and .gitlab-ci.yml). Build context is the
# repo root so the reactor POMs and .git are reachable.
#
# Pass the tag you apply so the running app can report it (see VersionController
# / the sidebar build stamp); reuse the same value you tag with so they can't drift:
#   docker build -f docker/community-plus-isva/Dockerfile \
#     -t ldapportal-community-plus-isva:v1.4.2 --build-arg IMAGE_VERSION=v1.4.2 .

# ── build stage: JDK + Maven, discarded — nothing here ships in the final image ──
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependency layer first: copy every reactor POM (all four child modules must be
# present or the aggregator fails to parse) plus .mvn, then prime the local repo.
# This layer is keyed only on the POMs, so ordinary source edits reuse the cached
# dependency download instead of re-fetching it — the Docker/Kaniko equivalent of
# the ~/.m2 cache the old setup-java / .maven jobs used to persist.
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY core/pom.xml core/
COPY addons/isva/pom.xml addons/isva/
COPY distribution/community/pom.xml distribution/community/
COPY distribution/community-plus-isva/pom.xml distribution/community-plus-isva/
RUN mvn -B -ntp -pl distribution/community-plus-isva -am -DskipTests go-offline || true
# Now the sources (+ .git, so git-commit-id-maven-plugin can bake the short-SHA
# into META-INF/build-info.properties). -am builds the upstream modules:
# community-plus-isva -> core + addons/isva.
COPY . .
RUN mvn -B -ntp -pl distribution/community-plus-isva -am -DskipTests clean package \
    && cp distribution/community-plus-isva/target/ldapportal-community-plus-isva-*.jar /build/app.jar

# ── runtime stage: JRE only, identical to the previous single-stage image ──
FROM eclipse-temurin:21-jre-alpine
# See root Dockerfile for rationale — pulls Alpine package patches
# that landed after the upstream Temurin image's last rebuild so
# Trivy doesn't flag HIGH/CRITICAL on a base-image lag.
RUN apk upgrade --no-cache
WORKDIR /app
COPY --from=build /build/app.jar app.jar
# Image tag, baked at build time and read back at runtime via IMAGE_VERSION.
# Empty when unset → the app falls back to its git SHA.
ARG IMAGE_VERSION=""
ENV IMAGE_VERSION=${IMAGE_VERSION}
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", \
    "-Xms256m", "-Xmx512m", \
    "-XX:+UseContainerSupport", \
    "-jar", "app.jar"]
