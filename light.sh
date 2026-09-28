#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Set the tip light's raw level: light.sh 0-100 (0 = off), or no argument to read it.
# The level only takes effect after the commit byte (66 3C FF). On the ES the LED is
# invisible below ~20 and stops getting brighter around ~48; viewer.py maps its slider
# onto that range.
set -e
cd "$(dirname "$0")"
if [ -z "$1" ]; then
    printf 'light level: %d\n' "0x$(./send.py 66 3c fe)"
    exit
fi
[[ "$1" =~ ^[0-9]+$ ]] && (( $1 <= 100 )) || { echo "usage: $0 [0-100]" >&2; exit 1; }
./send.py 66 3c "$(printf %02x "$1")" >/dev/null
./send.py 66 3c ff >/dev/null
echo "light -> $1"
