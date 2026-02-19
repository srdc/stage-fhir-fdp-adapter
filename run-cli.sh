#!/usr/bin/env bash
set -euo pipefail

# 1. Point to your Hadoop installation
# Ensure this matches the location of your winutils.exe
HADOOP_DIR="C:/hadoop-2.8.3"

# 2. Configure JVM Options
JVM_OPTS="--add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
          --add-opens=java.base/java.nio=ALL-UNNAMED \
          --add-opens=java.base/java.lang=ALL-UNNAMED \
          --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
          --add-opens=java.base/java.util=ALL-UNNAMED \
          --add-opens=java.base/sun.util.calendar=ALL-UNNAMED \
          -Dhadoop.home.dir=${HADOOP_DIR} \
          -Djava.library.path=${HADOOP_DIR}/bin"

# 3. Run the Application
echo "Launching Healthy Aging CLI..."
java $JVM_OPTS -jar target/stage-fhir-fdp-adapter_2.13-1.0.0.jar "$@"