FROM flink:2.2.1-java17 AS connector-downloader
USER root

ARG FLINK_CDC_VERSION=3.6.0-2.2
ARG POSTGRES_CDC_VERSION=3.6.0-2.2
ARG PAIMON_VERSION=2.0.0
ARG MYSQL_DRIVER_VERSION=8.4.0
ARG HADOOP_VERSION=3.4.2

RUN mkdir -p /connectors \
    && curl -fL "https://repo.maven.apache.org/maven2/org/apache/flink/flink-sql-connector-mysql-cdc/${FLINK_CDC_VERSION}/flink-sql-connector-mysql-cdc-${FLINK_CDC_VERSION}.jar" \
       -o "/connectors/flink-sql-connector-mysql-cdc-${FLINK_CDC_VERSION}.jar" \
    && curl -fL "https://repo.maven.apache.org/maven2/org/apache/flink/flink-sql-connector-postgres-cdc/${POSTGRES_CDC_VERSION}/flink-sql-connector-postgres-cdc-${POSTGRES_CDC_VERSION}.jar" \
       -o "/connectors/flink-sql-connector-postgres-cdc-${POSTGRES_CDC_VERSION}.jar" \
    && curl -fL "https://repo.maven.apache.org/maven2/org/apache/paimon/paimon-flink-2.2/${PAIMON_VERSION}/paimon-flink-2.2-${PAIMON_VERSION}.jar" \
       -o "/connectors/paimon-flink-2.2-${PAIMON_VERSION}.jar" \
    && curl -fL "https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/${MYSQL_DRIVER_VERSION}/mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar" \
       -o "/connectors/mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar" \
    && curl -fL "https://repo.maven.apache.org/maven2/org/apache/hadoop/hadoop-client-api/${HADOOP_VERSION}/hadoop-client-api-${HADOOP_VERSION}.jar" \
       -o "/connectors/hadoop-client-api-${HADOOP_VERSION}.jar" \
    && curl -fL "https://repo.maven.apache.org/maven2/org/apache/hadoop/hadoop-client-runtime/${HADOOP_VERSION}/hadoop-client-runtime-${HADOOP_VERSION}.jar" \
       -o "/connectors/hadoop-client-runtime-${HADOOP_VERSION}.jar"

FROM flink:2.2.1-java17

COPY --from=connector-downloader /connectors/ /opt/flink/lib/
USER root
RUN mkdir -p /tmp/flink-savepoints/checkpoints && chown -R flink:flink /tmp/flink-savepoints
