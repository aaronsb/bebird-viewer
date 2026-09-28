#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Start the viewer. It finds the Wi-Fi interface and joins the scope's network itself.
cd "$(dirname "$0")"
exec python3 viewer.py "$@"
