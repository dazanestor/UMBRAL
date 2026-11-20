#!/usr/bin/env bash
# Compila el APK de UMBRAL. Genera el wrapper de Gradle si falta y avisa de requisitos.
# Uso:  ./build.sh [debug|release]   (por defecto: debug)
set -euo pipefail
cd "$(dirname "$0")"

VARIANT="${1:-debug}"
GRADLE_VER="8.7"

say() { printf '\033[1;34m[umbral]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[umbral]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[umbral]\033[0m %s\n' "$*" >&2; exit 1; }

# 1) Wrapper de Gradle (el .jar binario no se versiona)
if [ ! -f gradlew ]; then
  say "No hay gradlew; generando el wrapper de Gradle $GRADLE_VER..."
  if command -v gradle >/dev/null 2>&1; then
    gradle wrapper --gradle-version "$GRADLE_VER"
  else
    die "Falta 'gradle' para generar el wrapper. Instálalo (p. ej. 'sdk install gradle $GRADLE_VER' con SDKMAN) o abre la carpeta en Android Studio (lo genera solo)."
  fi
fi

# 2) Requisitos externos
[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || warn "ANDROID_HOME/ANDROID_SDK_ROOT no definido; Gradle necesita el SDK de Android."
if [ ! -f app/google-services.json ]; then
  warn "Falta app/google-services.json (Firebase). El plugin de FCM fallará."
  warn "  -> Consola Firebase > tu proyecto > añade app Android 'es.qvadis.app' > descarga google-services.json a android/app/"
fi

# 3) Compilar
case "$VARIANT" in
  debug)   TASK="assembleDebug";   APK="app/build/outputs/apk/debug/app-debug.apk" ;;
  release) TASK="assembleRelease"; APK="app/build/outputs/apk/release/app-release.apk" ;;
  *) die "Variante desconocida: $VARIANT (usa debug o release)" ;;
esac

say "Compilando ($TASK)..."
./gradlew "$TASK"

if [ -f "$APK" ]; then
  say "APK listo: $APK"
else
  warn "No encuentro el APK esperado en $APK (revisa la salida de Gradle)."
fi
