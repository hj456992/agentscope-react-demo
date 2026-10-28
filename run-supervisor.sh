#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -f target/agentscope-react-demo.jar ]]; then
  echo '请先运行 mvn -q package' >&2
  exit 1
fi
exec java -Dfile.encoding=UTF-8 -cp target/agentscope-react-demo.jar dev.garden.demo.supervisor.SupervisorDemo "$@"
