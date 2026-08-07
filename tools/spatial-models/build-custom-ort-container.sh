#!/bin/bash
set -euo pipefail

# ORT v1.28.0 的 Android 自定义构建镜像仍基于 Ubuntu 20.04 / Python 3.8，
# 但 vcpkg_helpers.py 已使用 set[str]。延迟注解求值可保持官方镜像与源码版本不变。
helpers=/workspace/onnxruntime/tools/python/util/vcpkg_helpers.py
if ! grep -q '^from __future__ import annotations$' "${helpers}"; then
  sed -i '1i from __future__ import annotations' "${helpers}"
fi

if [[ -n "${EVERYTHINGDONE_PROXY_HOST:-}" ]]; then
  proxy_port="${EVERYTHINGDONE_PROXY_PORT:-7890}"
  proxy_url="http://${EVERYTHINGDONE_PROXY_HOST}:${proxy_port}"
  export HTTP_PROXY="${proxy_url}"
  export HTTPS_PROXY="${proxy_url}"
  export http_proxy="${proxy_url}"
  export https_proxy="${proxy_url}"
  export GRADLE_OPTS="${GRADLE_OPTS:-} \
    -Dhttp.proxyHost=${EVERYTHINGDONE_PROXY_HOST} -Dhttp.proxyPort=${proxy_port} \
    -Dhttps.proxyHost=${EVERYTHINGDONE_PROXY_HOST} -Dhttps.proxyPort=${proxy_port}"
fi

exec /bin/bash /workspace/scripts/build.sh "$@"
