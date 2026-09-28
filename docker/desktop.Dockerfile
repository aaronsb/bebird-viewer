# SPDX-License-Identifier: GPL-3.0-or-later
# Build environment for the standalone desktop binary (PyInstaller, one file).
# Ubuntu 22.04 (glibc 2.35) is the oldest mainstream base the current PyQt6 wheels
# (manylinux_2_34) install on, so the binary runs on glibc 2.35 and newer.
FROM ubuntu:22.04@sha256:b8b6ee6aa931ecd9d0d952abc34dc0e5f7c6a30c6bb71b079fe399fde0329c02

# python3-venv and libpython3.10 for the build venv and the bundled interpreter, binutils for PyInstaller's binary analysis, and the system
# libraries Qt's xcb and wayland platform plugins link against, so PyInstaller bundles them.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      python3 python3-venv libpython3.10 binutils \
      libglib2.0-0 libdbus-1-3 libfontconfig1 libfreetype6 libgl1 libegl1 \
      libx11-6 libx11-xcb1 libxkbcommon0 libxkbcommon-x11-0 libxcb1 libxcb-cursor0 \
      libxcb-icccm4 libxcb-image0 libxcb-keysyms1 libxcb-randr0 libxcb-render-util0 \
      libxcb-shape0 libxcb-xfixes0 libxcb-xinerama0 libxcb-xkb1 \
      libwayland-client0 libwayland-cursor0 libwayland-egl1 \
 && rm -rf /var/lib/apt/lists/*

# Dependencies are baked into the image, so the build container itself runs without network.
# Every package (pip included) is pinned by version and hash, wheels only; `make app-lock`
# regenerates the lock. The pip download cache lives in a BuildKit cache mount, not on the
# host or in the image.
COPY docker/desktop-requirements.lock /tmp/requirements.lock
RUN --mount=type=cache,target=/root/.cache/pip \
    python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --require-hashes --only-binary=:all: -r /tmp/requirements.lock

ENV PATH=/opt/venv/bin:$PATH \
    HOME=/tmp/home \
    PYTHONDONTWRITEBYTECODE=1
# Run as an unprivileged user unless the caller passes --user (the Makefile passes the host uid).
USER 65534:65534
WORKDIR /src
