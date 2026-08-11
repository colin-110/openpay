# One Dockerfile for every service in the monorepo.
#
# The build stage is identical for all of them, so Docker's layer cache builds the reactor once and
# every subsequent image reuses it. Only the final COPY differs, which is why MODULE and ARTIFACT
# are declared in the runtime stage rather than at the top: an ARG invalidates every layer after
# it, and declaring them early would rebuild the whole project nine times.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Poms first. Dependencies change far less often than source, so this layer survives most edits and
# saves re-downloading the world on every code change.
COPY pom.xml ./
COPY libs/common-observability/pom.xml libs/common-observability/
COPY libs/common-security/pom.xml libs/common-security/
COPY libs/common-kafka/pom.xml libs/common-kafka/
COPY libs/common-outbox/pom.xml libs/common-outbox/
COPY libs/common-audit/pom.xml libs/common-audit/
COPY services/gateway-service/pom.xml services/gateway-service/
COPY services/auth-service/pom.xml services/auth-service/
COPY services/merchant-service/pom.xml services/merchant-service/
COPY services/payment-service/pom.xml services/payment-service/
COPY services/provider-router-service/pom.xml services/provider-router-service/
COPY services/webhook-service/pom.xml services/webhook-service/
COPY services/ledger-service/pom.xml services/ledger-service/
COPY services/settlement-service/pom.xml services/settlement-service/
COPY services/notification-service/pom.xml services/notification-service/
COPY services/fraud-service/pom.xml services/fraud-service/
COPY services/mock-bank-service/pom.xml services/mock-bank-service/
COPY services/vault-service/pom.xml services/vault-service/
COPY services/demo-storefront/pom.xml services/demo-storefront/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY libs libs
COPY services services
# Tests are the build pipeline's job, not the image's. Running them here would need a Docker daemon
# inside the build for Testcontainers, and would repeat on every image.
RUN mvn -B -DskipTests package

# A runtime built to fit, rather than a general-purpose JRE.
#
# Measured on this repository: eclipse-temurin:21-jre is 493 MB, 21-jre-alpine is 286 MB, and the
# jlink image below is 62 MB for the same Java 21. Across thirteen services that is the difference
# between a ~10 GB local build and a ~3 GB one.
#
# A full JRE ships every module in the platform — CORBA-era leftovers, the compiler, JavaFX hooks,
# tooling this will never load. jlink assembles only the modules named, which is why the result is
# a fifth of the size while running the identical bytecode.
#
# The module list is deliberately generous rather than minimal. Trimming it further saves a few
# megabytes and risks a NoClassDefFoundError that appears only on the one code path nobody
# exercised before deploying — a bad trade. java.desktop is here because java.beans is, and Spring
# uses it everywhere; jdk.crypto.ec because TLS needs it; jdk.unsupported because Netty and several
# other libraries reach for sun.misc.Unsafe.
#
# jdk.net was learned the hard way: without it every service started, passed its own startup, and
# then died with ClassNotFoundException: jdk.net.Sockets the moment Tomcat configured a connector.
# jdk.localedata is included for the same reason in advance — a platform that formats currency
# should not discover at runtime that it only has the root locale.
FROM eclipse-temurin:25-jdk-alpine AS jre
RUN jlink \
        --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jfr,jdk.localedata,jdk.management,jdk.net,jdk.security.auth,jdk.unsupported,jdk.httpserver,jdk.naming.dns,jdk.zipfs \
        --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
        --output /javaruntime

# Alpine rather than the Temurin image, because the runtime now arrives from the stage above and
# nothing else in that image is wanted. The trade throughout is musl libc rather than glibc:
# Temurin publishes these Alpine builds officially and this platform is pure JVM — no JNI, no
# native agents — so nothing here can tell the difference. A service that later needed a
# glibc-linked native library would have to move back, and that is worth knowing before adding one.
FROM alpine:3.21 AS runtime

COPY --from=jre /javaruntime /opt/java
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:${PATH}"

# Which module this image runs. Declared here so the build stage above stays shared and cached.
ARG MODULE
ARG ARTIFACT

# curl is here for the container healthcheck, which hits /actuator/health rather than just probing
# the port. A port that is open says the JVM started; it says nothing about whether the service can
# reach its database.
#
# apk with --no-cache rather than apt: no package lists are written, so there is nothing to clean
# up afterwards and no risk of the cleanup being forgotten in a later edit.
RUN apk add --no-cache curl

# Runs unprivileged: a payment service that is compromised should not also be root in its
# container. Pinned to a fixed numeric uid/gid, not just a named user: Kubernetes' runAsNonRoot
# check has to verify the *number* a container will actually run as before it starts anything,
# and a named USER it cannot resolve to one is a refusal to start rather than an assumption in
# the container's favour. 10001 is arbitrary but fixed, so platform/k8s/30-services.yaml can
# state the same number in runAsUser rather than asking Kubernetes to trust the image.
#
# BusyBox adduser/addgroup, which take short flags only — the GNU long forms are silently a
# different tool here.
RUN addgroup -S -g 10001 openpay && adduser -S -u 10001 -G openpay -h /app -D openpay
WORKDIR /app

# --chown on the COPY rather than a RUN chown afterwards, and this is worth more than it looks: a
# RUN that touches every file writes a second copy of all of them into a new layer. The image
# history showed exactly that — a 39.5 MB jar layer followed by a 39.5 MB chown layer, doubling
# the only part of the image that is actually this service.
COPY --from=build --chown=10001:10001 /build/${MODULE}/target/${ARTIFACT}-*.jar /app/app.jar
USER 10001:10001

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then respects whatever the container is given,
# so changing the compose memory limit does not silently leave the heap wrong.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
