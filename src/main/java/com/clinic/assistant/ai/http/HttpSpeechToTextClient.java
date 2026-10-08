package com.clinic.assistant.ai.http;

import com.clinic.assistant.ai.SpeechToTextClient;
import com.clinic.assistant.common.AiUnavailableException;
import com.clinic.assistant.config.AiProperties;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;

/**
 * HTTP-клиент этапа STT: отправляет аудиофайл в сервис распознавания речи,
 * развёрнутый коллегой.
 *
 * <h2>Контракт сервиса</h2>
 * <pre>
 * POST {app.ai.stt.url}
 * Content-Type: multipart/form-data
 *   file     — аудиофайл (webm/wav/m4a/ogg, до 15 МБ)
 *   language — "ru"
 *
 * 200 OK
 * { "text": "записаться к терапевту завтра в десять утра", "model": "whisper-ru-v1" }
 * </pre>
 *
 * <p>Любой не-2xx ответ или сетевая ошибка превращаются в
 * {@link AiUnavailableException} → наружу уйдёт 502 с понятным сообщением,
 * а не стектрейс.</p>
 */
public class HttpSpeechToTextClient implements SpeechToTextClient {

    private final RestClient restClient;
    private final String url;

    public HttpSpeechToTextClient(AiProperties.Stage stage) {
        this.url = stage.getUrl();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(stage.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(stage.getReadTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public SpeechToTextResult transcribe(AudioContent audio) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new NamedByteArrayResource(audio.data(), audio.filename()));
        form.add("language", "ru");

        try {
            SttResponse response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(SttResponse.class);
            if (response == null || response.text() == null || response.text().isBlank()) {
                throw new AiUnavailableException("STT-сервис вернул пустой текст (url=" + url + ")");
            }
            return new SpeechToTextResult(response.text().trim(),
                    response.model() != null ? response.model() : "unknown");
        } catch (RestClientResponseException e) {
            throw new AiUnavailableException(
                    "STT-сервис ответил ошибкой " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new AiUnavailableException(
                    "Не удалось подключиться к STT-сервису (" + url + "): " + e.getMessage(), e);
        }
    }

    /** Ответ STT-сервиса. */
    public record SttResponse(String text, String model) {
    }

    /** Byte[]-ресурс с именем файла, чтобы в multipart-part подставилось имя. */
    static final class NamedByteArrayResource extends AbstractResource {
        private final byte[] content;
        private final String filename;

        NamedByteArrayResource(byte[] content, String filename) {
            this.content = content;
            this.filename = filename;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public long contentLength() {
            return content.length;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public String getDescription() {
            return "audio[" + content.length + " bytes] " + filename;
        }
    }
}
