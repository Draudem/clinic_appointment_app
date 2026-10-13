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

# --- Серверное распознавание речи (телефония) ---
# STT-модель GigaAM и нативная библиотека НЕ лежат в репозитории: их качает
# deploy/fetch-stt-model.sh и fetch-stt-libs.sh, а сюда deploy-local.sh копирует.
#
# ЗАЧЕМ нужна отдельная libonnxruntime.so (версия 1.17.1):
# JNI-библиотека sherpa-onnx собрана против неё и требует символ VERS_1.17.1,
# тогда как в проекте для NLU используется onnxruntime 1.22.0. Это РАЗНЫЕ SONAME
# (libonnxruntime.so и libonnxruntime.so.1), поэтому обе спокойно живут в одном
# процессе. Подменять версию NLU на 1.17.1 нельзя — модель коллеги собрана под 1.22.
COPY model.int8.onnx /app/models/stt/model.int8.onnx
COPY tokens.txt /app/models/stt/tokens.txt
COPY libonnxruntime.so /app/lib/libonnxruntime.so

# LD_LIBRARY_PATH обязателен: без него JNI не находит libonnxruntime.so при старте,
# STT молча не загружается и порт AudioSocket не поднимается.
ENV LD_LIBRARY_PATH=/app/lib \
    STT_ENABLED=true \
    STT_AUDIOSOCKET_PORT=8091 \
    STT_MODEL_DIR=/app/models/stt

# Порт должен совпадать с SERVER_PORT из docker-compose.yml и с ports-маппингом
EXPOSE 8090
# TCP-порт AudioSocket — сюда подключается Asterisk (не HTTP!)
EXPOSE 8091

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
