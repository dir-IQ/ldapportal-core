# SPDX-License-Identifier: Apache-2.0
#
# Community-plus-ISVA image. Ships under Apache 2.0 with core/ + addons/isva
# only — no ee/ bytecode. The verify-no-ee-bytecode antrun check in
# distribution/community-plus-isva/pom.xml fails the Maven build if anything
# ee leaks into this JAR, so by the time the COPY below runs the artifact is
# already license-clean.
#
# Build context is the repo root so the COPY can reach distribution/.
# Pass the tag you apply so the running app can report it (see VersionController
# / the sidebar build stamp); reuse the same value you tag with so they can't drift:
#   docker build -f docker/community-plus-isva/Dockerfile \
#     -t ldapportal-community-plus-isva:v1.4.2 --build-arg IMAGE_VERSION=v1.4.2 .
FROM eclipse-temurin:21-jre-alpine
# See root Dockerfile for rationale — pulls Alpine package patches
# that landed after the upstream Temurin image's last rebuild so
# Trivy doesn't flag HIGH/CRITICAL on a base-image lag.
RUN apk upgrade --no-cache
WORKDIR /app
COPY distribution/community-plus-isva/target/ldapportal-community-plus-isva-*.jar app.jar
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
