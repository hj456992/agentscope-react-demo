#!/usr/bin/env bash
# 用法：docs/tools/md2pdf/build.sh [输入.md] [输出.pdf]
# 不传参数时，默认把 docs/项目问题及解法.md 渲染成同名 PDF。
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

if [ ! -d "$here/node_modules" ]; then
    echo "首次运行，安装 md2pdf 依赖……"
    npm --prefix "$here" install --no-audit --no-fund
fi

exec node "$here/md2pdf.mjs" "$@"
