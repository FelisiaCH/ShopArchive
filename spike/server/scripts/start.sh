#!/bin/sh
# Spike server launcher (T4). Root defaults to this folder.
cd "$(dirname "$0")" || exit 1
mkdir -p tmp crash-reports
exec java -Xmx512m -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -Djava.io.tmpdir=tmp -Djna.tmpdir=tmp -XX:-UsePerfData -XX:ErrorFile=crash-reports/hs_err_%p.log \
  -jar spike-server.jar "$@"
