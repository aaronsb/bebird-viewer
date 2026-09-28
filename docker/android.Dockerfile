# SPDX-License-Identifier: GPL-3.0-or-later
# Build environment for the Android app: JDK 17 + Android SDK (platform 35, build-tools 34, the
# version AGP 8.7 defaults to). platform-tools only so AGP does not try, and fail, to install it
# into the read-only SDK on every build.
# Used by `make android-*` and by CI; see the Makefile for how it is run (as the calling user).
FROM eclipse-temurin:17-jdk-jammy@sha256:60fcdd4a85c94a23ef86d0d55867c6a8dbc4f7c3efc8286e259956c3ea4ef08c

# Android command-line tools 19.0; checksum from dl.google.com/android/repository/repository2-3.xml
ARG CMDLINE_TOOLS=commandlinetools-linux-13114758_latest.zip
ARG CMDLINE_TOOLS_SHA1=5fdcc763663eefb86a5b8879697aa6088b041e70
ARG UID=1000
ARG GID=1000

ENV ANDROID_HOME=/opt/android-sdk \
    HOME=/home/builder \
    GRADLE_USER_HOME=/home/builder/.gradle \
    ANDROID_USER_HOME=/home/builder/.gradle/android
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSLo /tmp/tools.zip https://dl.google.com/android/repository/$CMDLINE_TOOLS \
 && echo "$CMDLINE_TOOLS_SHA1  /tmp/tools.zip" | sha1sum -c - \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager --install "platforms;android-35" "build-tools;34.0.0" "platform-tools" >/dev/null \
 && rm -rf /root/.android /root/.cache

# The home directory (and the Gradle cache volume mounted on it) belongs to the build user,
# so a fresh named volume starts out writable by that uid.
RUN mkdir -p $ANDROID_USER_HOME && chown -R $UID:$GID $HOME
USER $UID:$GID
WORKDIR /work
