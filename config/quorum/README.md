生成 Kafka 集群 UUID
kafka-storage.bat random-uuid

# 初始化kraft所需的元数据文件

kafka-storage.bat format -t yA1W3s_mSpuyVNmqTMNsQQ -c D:\workspace_java\automq\config\kraft\server.properties
#创建topic
kafka-topics.bat --create --topic quickstart-events --bootstrap-server localhost:9092
kafka-topics.bat --bootstrap-server localhost:9092 --describe --topic quickstart-events


单条发布
# Windows（PowerShell）
kafka-console-producer.bat --topic quickstart-events --bootstrap-server localhost:9092

kafka-console-consumer.bat --topic quickstart-events --from-beginning --bootstrap-server localhost:9092

批量发布

# Windows（PowerShell）
kafka-producer-perf-test.bat --topic quickstart-events --num-records 1000000 --record-size 1024 --throughput 1 --producer-props bootstrap.servers=localhost:9092

kafka-consumer-perf-test.bat --bootstrap-server localhost:9092 --topic quickstart-events --messages 1000000 --threads 2 --timeout 600000 --reporting-interval 1000 --show-detailed-stats


# 启动参数

# JVM参数
-Xmx1G -Xms1G -server -XX:+UseZGC -XX:MaxDirectMemorySize=2G -Dkafka.logs.dir=logs/ -Dlog4j.configuration=file:config/log4j.properties -Dio.netty.leakDetection.level=paranoid -Dio.netty.leakDetection.targetRecords=50

# CLI 参数
config/kraft/server_idc_a.properties

# 环境变量
KAFKA_S3_ACCESS_KEY=minioadmin;KAFKA_S3_SECRET_KEY=minioadmin

## JDK17 和gradle 8.8
记得设置java compile到17
