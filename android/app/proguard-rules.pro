# SPDX-License-Identifier: GPL-3.0-or-later
# R8 rules for the release build, on top of proguard-android-optimize.txt and the rules the
# libraries ship. Nothing is loaded by name: the manifest's classes are kept by AAPT's rules, the
# ViewModel's constructor by lifecycle-viewmodel's, and the enums stored by name (theme, scale
# style, annotate tool) keep their names because R8 leaves Enum.name() alone.

# Stack traces keep line numbers; build/outputs/mapping/release/mapping.txt maps them back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
