#!/bin/sh
# Gradle wrapper launcher script.
# Jalankan `gradle wrapper` sekali di mesin dengan Gradle terinstal
# untuk men-generate gradle-wrapper.jar yang sebenarnya, atau unduh
# dari https://github.com/gradle/gradle (lisensi Apache 2.0), lalu
# taruh di gradle/wrapper/gradle-wrapper.jar.
#
# Sekali gradle-wrapper.jar tersedia, script ini otomatis berfungsi.

DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
