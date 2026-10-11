# Бэкенд клиники (авторизация + голосовая запись + NLU-модель) для деплоя на сервер.
# Сборка идёт на сервере, контекст = папка, куда скопированы Dockerfile, JAR и файлы модели:
#   docker build -t clinic-backend .
# JAR собирается локально: ./gradlew bootJar -> build/libs/clinic-backend-0.1.0.jar
# Модель (model.onnx + tokenizer.json) копируется отдельно deploy/deploy-local.sh.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Имя файла фиксировано — совпадает с именем артефакта Gradle
COPY clinic-backend-0.1.0.jar /app/app.jar

# Локальная NLU-модель коллеги из ai_model/clinic_lm/models.
# ВАЖНО: model.onnx и tokenizer.json — ПАРНЫЕ (у каждого чекпоинта свой vocab),
# смешивать файлы из разных папок нельзя.
COPY model.onnx /app/models/model.onnx
COPY tokenizer.json /app/models/tokenizer.json

ENV NLU_MODEL_PATH=/app/models/model.onnx \
    NLU_TOKENIZER_PATH=/app/models/tokenizer.json \
    VOICE_PARSER=nlu \
    LLM_ENABLED=false

# Порт должен совпадать с SERVER_PORT из docker-compose.yml и с ports-маппингом
EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
