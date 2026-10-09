# Бэкенд-минимум (авторизация) для деплоя на общий сервер.
# Сборка идёт на сервере, контекст = папка, куда скопированы этот файл и JAR:
#   docker build -t clinic-backend .
# JAR собирается локально: ./gradlew bootJar -> build/libs/clinic-backend-0.1.0.jar
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Имя файла фиксировано — совпадает с именем артефакта Gradle
COPY clinic-backend-0.1.0.jar /app/app.jar

# Порт должен совпадать с SERVER_PORT из docker-compose.yml и с ports-маппингом
EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
