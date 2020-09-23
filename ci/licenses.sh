#!/bin/bash
#
# Copyright 2013 The Flutter Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

set -e
shopt -s nullglob

# Needed because if it is set, cd may print the path it changed to.
unset CDPATH

# On Mac OS, readlink -f doesn't work, so follow_links traverses the path one
# link at a time, and then cds into the link destination and find out where it
# ends up.
#
# The function is enclosed in a subshell to avoid changing the working directory
# of the caller.
function follow_links() (
  cd -P "$(dirname -- "$1")"
  file="$PWD/$(basename -- "$1")"
  while [[ -h "$file" ]]; do
    cd -P "$(dirname -- "$file")"
    file="$(readlink -- "$file")"
    cd -P "$(dirname -- "$file")"
    file="$PWD/$(basename -- "$file")"
  done
  echo "$file"
)

SCRIPT_DIR=$(follow_links "$(dirname -- "${BASH_SOURCE[0]}")")
SRC_DIR="$(cd "$SCRIPT_DIR/../.."; pwd -P)"
DART_BIN="$SRC_DIR/third_party/dart/tools/sdks/dart-sdk/bin"
PATH="$DART_BIN:$PATH"

echo "Verifying license script is still happy..."
echo "Using pub from $(command -v pub), dart from $(command -v dart)"

untracked_files="$(cd "$SRC_DIR/flutter"; git status --ignored --short | grep -E "^!" | awk "{print\$2}")"
untracked_count="$(echo "$untracked_files" | wc -l)"
if [[ $untracked_count -gt 0 ]]; then
  echo ""
  echo "WARNING: There are $untracked_count untracked/ignored files or directories in the flutter repository."
  echo "False positives may occur."
  echo "You can use 'git clean -dxf' in the flutter dir to clean out these files."
  echo "BUT, be warned that this will recursively remove all these files and directories:"
  echo "$untracked_files"
  echo ""
fi

dart --version

# Collects the license information from the repo.
# Runs in a subshell.
function collect_licenses() (
  cd "$SRC_DIR/flutter/tools/licenses"
  pub get
  dart --enable-asserts lib/main.dart         \
    --src ../../..                            \
    --out ../../../out/license_script_output  \
    --golden ../../ci/licenses_golden
)

# Verifies the licenses in the repo.
# Runs in a subshell.
function verify_licenses() (
  local exitStatus=0
  cd "$SRC_DIR"

  # These files trip up the script on Mac OS X.
  find . -name ".DS_Store" -exec rm {} \;

  collect_licenses

  for f in out/license_script_output/licenses_*; do
      if ! cmp -s "flutter/ci/licenses_golden/$(basename "$f")" "$f"; then
          echo "============================= ERROR ============================="
          echo "License script got different results than expected for $f."
          echo "Please rerun the licenses script locally to verify that it is"
          echo "correctly catching any new licenses for anything you may have"
          echo "changed, and then update this file:"
          echo "  flutter/sky/packages/sky_engine/LICENSE"
          echo "For more information, see the script in:"
          echo "  https://github.com/flutter/engine/tree/master/tools/licenses"
          echo ""
          diff -U 6 "flutter/ci/licenses_golden/$(basename "$f")" "$f"
          echo "================================================================="
          echo ""
          exitStatus=1
      fi
  done

  echo "Verifying license tool signature..."
  if ! cmp -s "flutter/ci/licenses_golden/tool_signature" "out/license_script_output/tool_signature"; then
      echo "============================= ERROR ============================="
      echo "The license tool signature has changed. This is expected when"
      echo "there have been changes to the license tool itself. Licenses have"
      echo "been re-computed for all components. If only the license script has"
      echo "changed, no diffs are typically expected in the output of the"
      echo "script. Verify the output, and if it looks correct, update the"
      echo "license tool signature golden file:"
      echo "  ci/licenses_golden/tool_signature"
      echo "For more information, see the script in:"
      echo "  https://github.com/flutter/engine/tree/master/tools/licenses"
      echo ""
      diff -U 6 "flutter/ci/licenses_golden/tool_signature" "out/license_script_output/tool_signature"
      echo "================================================================="
      echo ""
      exitStatus=1
  fi

  echo "Checking license count in licenses_flutter..."

  local actualLicenseCount
  actualLicenseCount="$(tail -n 1 flutter/ci/licenses_golden/licenses_flutter | tr -dc '0-9')"
  local expectedLicenseCount=2 # When changing this number: Update the error message below as well describing all expected license types.

  if [[ $actualLicenseCount -ne $expectedLicenseCount ]]; then
      echo "=============================== ERROR ==============================="
      echo "The total license count in flutter/ci/licenses_golden/licenses_flutter"
      echo "changed from $expectedLicenseCount to $actualLicenseCount."
      echo "It's very likely that this is an unintentional change. Please"
      echo "double-check that all newly added files have a BSD-style license"
      echo "header with the following copyright:"
      echo "    Copyright 2013 The Flutter Authors. All rights reserved."
      echo "Files in 'third_party/txt' may have an Apache license header instead."
      echo "If you're absolutely sure that the change in license count is"
      echo "intentional, update 'flutter/ci/licenses.sh' with the new count."
      echo "================================================================="
      echo ""
      exitStatus=1
  fi

  if [[ $exitStatus -eq 0 ]]; then
    echo "Licenses are as expected."
  fi
  return $exitStatus
)

verify_licenses