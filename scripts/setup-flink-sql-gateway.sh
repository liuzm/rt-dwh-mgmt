#!/usr/bin/env bash
set -euo pipefail

FLINK_CDC_VERSION="${FLINK_CDC_VERSION:-3.6.0-2.2}"
PAIMON_VERSION="${PAIMON_VERSION:-2.0.0}"
MYSQL_DRIVER_VERSION="${MYSQL_DRIVER_VERSION:-8.4.0}"
HADOOP_VERSION="${HADOOP_VERSION:-3.4.2}"
MAVEN_REPOSITORY="${MAVEN_REPOSITORY:-https://repo.maven.apache.org/maven2}"

if [[ -z "${FLINK_HOME:-}" ]]; then
  echo "FLINK_HOME is required, for example:" >&2
  echo "  FLINK_HOME=/path/to/flink-2.2.1 $0" >&2
  exit 1
fi

if [[ ! -x "${FLINK_HOME}/bin/sql-gateway.sh" || ! -d "${FLINK_HOME}/lib" ]]; then
  echo "Invalid FLINK_HOME: ${FLINK_HOME}" >&2
  exit 1
fi

SETUP_TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${SETUP_TEMP_DIR}"' EXIT

download_verified() {
  local url="$1"
  local file_name="$2"
  local target="${SETUP_TEMP_DIR}/${file_name}"

  curl -fsSL "${url}" -o "${target}"
  curl -fsSL "${url}.sha1" -o "${target}.sha1"

  local expected actual
  expected="$(awk '{print $1}' "${target}.sha1")"
  actual="$(shasum -a 1 "${target}" | awk '{print $1}')"
  if [[ "${expected}" != "${actual}" ]]; then
    echo "Checksum verification failed: ${file_name}" >&2
    exit 1
  fi

  install -m 0644 "${target}" "${FLINK_HOME}/lib/${file_name}"
  echo "Installed ${file_name}"
}

download_verified \
  "${MAVEN_REPOSITORY}/org/apache/flink/flink-sql-connector-mysql-cdc/${FLINK_CDC_VERSION}/flink-sql-connector-mysql-cdc-${FLINK_CDC_VERSION}.jar" \
  "flink-sql-connector-mysql-cdc-${FLINK_CDC_VERSION}.jar"

download_verified \
  "${MAVEN_REPOSITORY}/org/apache/paimon/paimon-flink-2.2/${PAIMON_VERSION}/paimon-flink-2.2-${PAIMON_VERSION}.jar" \
  "paimon-flink-2.2-${PAIMON_VERSION}.jar"

download_verified \
  "${MAVEN_REPOSITORY}/com/mysql/mysql-connector-j/${MYSQL_DRIVER_VERSION}/mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar" \
  "mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar"

# Flink's standard distribution intentionally excludes Hadoop classes. Paimon
# references Hadoop's Configuration/FileSystem APIs while planning sink jobs,
# even when the warehouse itself is on the local filesystem.
download_verified \
  "${MAVEN_REPOSITORY}/org/apache/hadoop/hadoop-client-api/${HADOOP_VERSION}/hadoop-client-api-${HADOOP_VERSION}.jar" \
  "hadoop-client-api-${HADOOP_VERSION}.jar"

download_verified \
  "${MAVEN_REPOSITORY}/org/apache/hadoop/hadoop-client-runtime/${HADOOP_VERSION}/hadoop-client-runtime-${HADOOP_VERSION}.jar" \
  "hadoop-client-runtime-${HADOOP_VERSION}.jar"

echo
echo "Connector installation completed. Restart the Flink cluster, then start SQL Gateway:"
echo "  ${FLINK_HOME}/bin/stop-cluster.sh"
echo "  ${FLINK_HOME}/bin/start-cluster.sh"
echo "  ${FLINK_HOME}/bin/sql-gateway.sh start -Dsql-gateway.endpoint.rest.address=localhost -Dsql-gateway.endpoint.rest.port=9083"
