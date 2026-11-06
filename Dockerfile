# Build environment for Glashutte (Android app). Contains the JDK, Android SDK, NDK and CMake
# needed to build the app, with or without the proprietary VITURE SDK.
#
# The VITURE SDK is NEVER part of this image: it is mounted read-only at run time (see the
# Makefile). The image contains no source code either; the source tree is mounted too.
#
# NOTE: the image bundles Google's Android SDK/NDK, which are covered by Google's own
# licence terms (accepted below). Build it locally; do not push it to a public registry.
#
# Keep these pins in sync with Android/*/build.gradle.kts (compileSdk, ndkVersion, cmake).
ARG BASE_IMAGE=docker.io/library/ubuntu:24.04
FROM ${BASE_IMAGE}

# Command-line tools 22.0: API 37 platforms require the newer repository metadata.
ARG CMDLINE_TOOLS_VERSION=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_PLATFORM=android-37.0
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK=30.0.14904198
ARG ANDROID_CMAKE=4.1.2

LABEL org.opencontainers.image.title="glashutte-build" \
      org.opencontainers.image.description="Build environment for Glashutte. Contains no VITURE SDK and no source code." \
      org.opencontainers.image.source="https://github.com/dmulder/glashutte"

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip rsync git openjdk-21-jdk-headless \
 && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk

# Use IPv4 sockets for both SDK installation and runtime Gradle downloads.
ENV JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"

RUN curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
 && echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - \
 && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
 && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm /tmp/cmdline-tools.zip \
 && (yes || true) | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --verbose --licenses \
 && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --verbose \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "ndk;${ANDROID_NDK}" \
        "cmake;${ANDROID_CMAKE}" \
 && chmod -R a+rX "${ANDROID_HOME}"

# The container runs as an arbitrary (host) uid, so caches and the scratch build tree live in
# world-writable directories that are meant to be backed by named volumes. (The uid usually has
# no passwd entry, so the JVM's user.home is pinned explicitly.)
ENV HOME=/cache/home \
    GRADLE_USER_HOME=/cache/gradle \
    ANDROID_USER_HOME=/cache/android \
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Duser.home=/cache/home"
RUN mkdir -p /cache/home /cache/gradle /cache/android /work /out \
 && chmod -R 0777 /cache /work /out

COPY docker/entrypoint.sh /usr/local/bin/glashutte-build
RUN chmod 0755 /usr/local/bin/glashutte-build

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/glashutte-build"]
CMD ["help"]
