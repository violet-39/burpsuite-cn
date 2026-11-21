#!/usr/bin/env bash
# 构建 burpsuite-cn.jar（需要 JDK 8+，推荐 21）
set -euo pipefail
cd "$(dirname "$0")"

ASM_VERSION=9.11.1
ASM_JAR="lib/asm-$ASM_VERSION.jar"

if [ ! -f "$ASM_JAR" ]; then
  mkdir -p lib
  echo "downloading ASM $ASM_VERSION ..."
  curl -fsSL -o "$ASM_JAR" "https://repo1.maven.org/maven2/org/ow2/asm/asm/$ASM_VERSION/asm-$ASM_VERSION.jar"
fi

rm -rf out
mkdir -p out
javac -encoding UTF-8 --release 8 -nowarn -cp "$ASM_JAR" -d out src/leon406/*.java
cp res/cn.txt res/white.txt out/

(
  cd out
  jar xf "../$ASM_JAR"
  rm -f module-info.class
  rm -rf META-INF/maven
)

jar cfm burpsuite-cn.jar manifest.txt -C out .
echo "built: burpsuite-cn.jar ($(du -h burpsuite-cn.jar | cut -f1))"
