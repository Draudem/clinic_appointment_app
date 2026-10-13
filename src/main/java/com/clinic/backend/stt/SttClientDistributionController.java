package com.clinic.backend.stt;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Раздача тестового клиента и образца аудио — чтобы напарнику не нужно было
 * ничего копировать и устанавливать.
 *
 * СХЕМА ПРОВЕРКИ (одна команда на чужой машине, где есть только python3):
 *
 *   python3 -c "import urllib.request as u; exec(u.urlopen('http://АДРЕС:8080/api/stt/public/client').read())"
 *
 * Сервер отдаёт скрипт с уже подставленным своим адресом; клиент сам скачивает
 * образец аудио (/sample.wav), прогоняет его через AudioSocket и проверяет,
 * что текст вернулся. Ставить ничего не нужно — только стандартная библиотека.
 *
 * ЗАЧЕМ ТАК СЛОЖНО: при наладке время уходит не на Asterisk, а на «скрипт не
 * нашёлся», «не тот путь» и «перепутанный IP». Здесь всё это снято: один адрес,
 * ноль файлов для переноса.
 *
 * ОБРАТИТЬ ВНИМАНИЕ: файлы публичны. Перед выкладкой наружу закрыть вместе с
 * остальным /api/stt/public/** (app.stt.public-read=false).
 */
@RestController
@RequestMapping("/api/stt/public")
public class SttClientDistributionController {

    private static final Logger log = LoggerFactory.getLogger(SttClientDistributionController.class);

    private static final String CLIENT_RESOURCE = "stt/client.py.txt";
    private static final String SAMPLE_RESOURCE = "stt/sample.wav";

    private final String configuredHost;
    private final boolean enabled;
    private final String audioPort;

    /** Шаблон клиента; адрес подставляется при каждой отдаче. */
    private String clientTemplate;
    private byte[] sampleWav;

    public SttClientDistributionController(
            @Value("${app.stt.advertised-host:}") String configuredHost,
            @Value("${app.stt.public-read:true}") boolean enabled,
            @Value("${app.stt.audiosocket-port:8091}") String audioPort) {
        this.enabled = enabled;
        this.audioPort = audioPort;
        this.configuredHost = configuredHost;
    }

    @PostConstruct
    void prepare() {
        try {
            clientTemplate = new String(readBytes(CLIENT_RESOURCE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Не удалось прочитать шаблон клиента: {}", e.getMessage());
        }
        try {
            sampleWav = readBytes(SAMPLE_RESOURCE);
            log.info("Образец аудио для проверки связи готов: {} КБ", sampleWav.length / 1024);
        } catch (IOException e) {
            log.error("Не удалось прочитать образец аудио: {}", e.getMessage());
        }
    }

    /**
     * Какой адрес подставить в клиента.
     *
     * Порядок важен:
     *  1) явная настройка app.stt.advertised-host — если IP известен заранее;
     *  2) заголовок Host из текущего запроса — самый надёжный вариант: подставляется
     *     ровно тот адрес, по которому клиент САМ пришёл за скриптом
     *     (именно это избавляет от ошибок вида 10.0.2.15 -> 10.2.0.15);
     *  3) первый не-петлевой IPv4 интерфейса машины.
     *
     * Раньше здесь стояло InetAddress.getLocalHost(), и он возвращал 127.0.1.1 —
     * подставлялся адрес, по которому напарник точно не достучится.
     */
    private String resolveHost(HttpServletRequest request) {
        if (configuredHost != null && !configuredHost.isBlank()) {
            return configuredHost;
        }
        String hostHeader = request.getHeader("Host");
        if (hostHeader != null && !hostHeader.isBlank()) {
            String h = hostHeader;
            if (h.startsWith("[")) {                      // IPv6: [::1]:8080
                int end = h.indexOf(']');
                h = end > 0 ? h.substring(1, end) : h;
            } else {
                int colon = h.indexOf(':');
                if (colon > 0) {
                    h = h.substring(0, colon);
                }
            }
            return h;
        }
        return firstNonLoopbackIp();
    }

    /** Первый не-петлевой IPv4 адрес машины. */
    private static String firstNonLoopbackIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || !nic.isUp()) {
                    continue;
                }
                for (java.net.InetAddress addr : java.util.Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // падаем на localhost
        }
        return "localhost";
    }

    private static byte[] readBytes(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return in.readAllBytes();
        }
    }

    @GetMapping(value = "/client", produces = "text/plain; charset=utf-8")
    public ResponseEntity<byte[]> client(HttpServletRequest request) {
        if (!enabled) {
            return ResponseEntity.status(403).body("// отладочный доступ выключен".getBytes(StandardCharsets.UTF_8));
        }
        if (clientTemplate == null) {
            return ResponseEntity.status(500)
                    .body("// клиент не подготовлен, смотрите логи сервера".getBytes(StandardCharsets.UTF_8));
        }
        // Адрес берём из того запроса, которым клиент пришёл за скриптом
        String host = resolveHost(request);
        String script = clientTemplate
                .replace("HOST_PLACEHOLDER", "HOST = \"" + host + "\"")
                .replace("HTTP_PORT_PLACEHOLDER", "HTTP_PORT = \"" + httpPortOf(request) + "\"")
                .replace("AUDIO_PORT_PLACEHOLDER", "AUDIO_PORT = \"" + audioPort + "\"");
        log.info("Клиент проверки связи выдан с адресом {}", host);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"stt-client.py\"")
                .body(script.getBytes(StandardCharsets.UTF_8));
    }

    /** HTTP-порт из заголовка Host, чтобы клиент ходил туда же, куда пришёл. */
    private static String httpPortOf(HttpServletRequest request) {
        String h = request.getHeader("Host");
        if (h == null) {
            return "8080";
        }
        int idx = h.lastIndexOf(':');
        if (idx > 0 && h.indexOf(']') < idx) {
            String p = h.substring(idx + 1).trim();
            if (!p.isEmpty() && p.chars().allMatch(Character::isDigit)) {
                return p;
            }
        }
        return "8080";
    }

    @GetMapping(value = "/sample.wav", produces = "audio/wav")
    public ResponseEntity<byte[]> sample() {
        if (!enabled) {
            return ResponseEntity.status(403).build();
        }
        if (sampleWav == null) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("audio/wav"))
                .contentLength(sampleWav.length)
                .body(sampleWav);
    }
}