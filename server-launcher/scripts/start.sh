#!/bin/sh
# ShopArchive server launcher. Root defaults to this folder.
cd "$(dirname "$0")" || exit 1

JAVA=java
if [ -n "$JAVA_HOME" ]; then JAVA="$JAVA_HOME/bin/java"; fi

MEM="${SHOPARCHIVE_MEMORY:-1G}"

# tmp/jvm and crash-reports may not exist yet: the launcher creates them, after checking they are no links.
# exec: signals (SIGTERM from systemd, Ctrl+C, ...) reach the JVM directly instead of a shell wrapper.
exec "$JAVA" -Xms"$MEM" -Xmx"$MEM" \
  -Dshoparchive.start-script=true \
  -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -Djava.io.tmpdir=tmp/jvm -XX:-UsePerfData -XX:ErrorFile=crash-reports/hs_err_pid%p.log \
  -jar shoparchive-server.jar "$@"
