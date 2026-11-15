#!/usr/bin/env bash
# Prints the body of a GitHub release: the version's CHANGELOG.md section followed by install steps.
#
#   release-notes.sh <version> <apk>      e.g. release-notes.sh 1.0.3 dist/PicACGMD-v1.0.3.apk
#
# SIGNED=false marks a build signed with a throwaway key (it cannot update an existing install).
# Fails when CHANGELOG.md has no entry for the version, so a release never goes out without notes.
set -euo pipefail

version=${1:?usage: release-notes.sh <version> <apk>}
apk=${2:?usage: release-notes.sh <version> <apk>}
changelog="$(dirname "$0")/../../CHANGELOG.md"

# Everything between "## [<version>]" (an optional " - date" may follow) and the next "## [" heading.
section=$(awk -v heading="## [$version]" '
  /^## \[/ { if (found) exit; found = (index($0, heading) == 1); next }
  found { print }
' "$changelog" | sed -e '/./,$!d')

if [ -z "${section//[[:space:]]/}" ]; then
  echo "CHANGELOG.md has no entry for $version" >&2
  exit 1
fi

name=$(basename "$apk")
sha=$(sha256sum "$apk" | cut -d' ' -f1)
if [ "${SIGNED:-true}" = true ]; then
  upgrade="已安装旧版本时直接覆盖安装，数据会保留。"
else
  upgrade="此安装包使用一次性密钥签名，不能覆盖已有安装；如已安装其他来源的版本，需要先卸载。"
fi

cat <<EOF
$section

## 安装

1. 下载下方的 \`$name\`。
2. 在手机上打开该文件并按提示安装；首次安装时需要允许浏览器或文件管理器「安装未知应用」。
3. $upgrade

需要 Android 8.0 或更高版本。

\`$name\` SHA-256：\`$sha\`
EOF
