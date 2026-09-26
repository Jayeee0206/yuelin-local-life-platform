FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
# Activity DATETIME values and JDBC configuration use Asia/Shanghai.
ENV TZ=Asia/Shanghai
WORKDIR /app
RUN useradd --system --create-home --uid 10001 yuelin \
    && mkdir -p /app/data/images \
    && chown -R yuelin:yuelin /app
COPY --from=builder /workspace/target/yuelin-local-life-1.0.0.jar /app/yuelin-local-life.jar
USER yuelin
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/yuelin-local-life.jar"]
