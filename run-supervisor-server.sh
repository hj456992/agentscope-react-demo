#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -f target/agentscope-react-demo-boot.jar ]]; then
  echo '请先运行 mvn -q package' >&2
  exit 1
fi
# 显式由环境传入令牌映射；Spring 配置缺失时拒绝启动。
if [[ -z "${SUPERVISOR_AUTH_TOKENS:-}" ]]; then
  echo '请先配置 SUPERVISOR_AUTH_TOKENS，例如 token=tenant:user:query_weather,query_trains,search_knowledge,memory:write' >&2
  exit 1
fi
exec java -Dfile.encoding=UTF-8 -jar target/agentscope-react-demo-boot.jar "$@"
