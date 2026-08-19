#!/usr/bin/env bash
# Extracts <licenses> from each artifact's published POM.
# Input: GAV lines on stdin ("group:artifact:version"). Output: GAV | license entries.
# ponytail: plain curl+sed, no XML toolchain needed for flat POM license blocks
set -u
TMP=$(mktemp -d)
while IFS= read -r gav; do
  group=${gav%%:*}; rest=${gav#*:}; name=${rest%%:*}; ver=${rest##*:}
  path="${group//./\/}/$name/$ver/$name-$ver.pom"
  f="$TMP/$(echo "$gav" | tr ':' '_').pom"
  curl -sfL "https://repo1.maven.org/maven2/$path" -o "$f" \
    || curl -sfL "https://dl.google.com/android/maven2/$path" -o "$f" \
    || { echo "$gav | FETCH-FAILED"; continue; }
  # capture <name>/<url> pairs inside <license> blocks
  lics=$(sed -n '/<licen[se]*>/,/<\/licen[se]*>/p' "$f" \
    | sed -n 's/.*<name>\(.*\)<\/name>.*/\1/p; s/.*<url>\(.*\)<\/url>.*/\1/p' \
    | paste -sd' ' -)
  [ -z "$lics" ] && lics="NO-LICENSE-ELEMENT"
  echo "$gav | $lics"
done
rm -rf "$TMP"
