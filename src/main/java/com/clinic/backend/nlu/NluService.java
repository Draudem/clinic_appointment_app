package com.clinic.backend.nlu;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Фасад NLU-этапа: единая точка для остальных слоёв. Скрывает, какая именно
 * реализация подключена (ONNX-модель, LLM, заглушка).
 */
@Service
public class NluService {

    private final NluModel model;

    public NluService(NluModel model) {
        this.model = model;
    }

    public NluFrame parse(String text) {
        return model.parse(normalize(text));
    }

    /**
     * Приводит реплику к виду, на котором модель обучена.
     *
     * ЗАЧЕМ. Модель обучена на синтетике в нижнем регистре и к регистру крайне
     * чувствительна: «запишите к терапевту» -> BOOK, «Запишите к терапевту» -> CANCEL,
     * «ЗАПИШИТЕ К ТЕРАПЕВТУ» -> UNSUPPORTED. Сейчас это не видно только потому,
     * что GigaAM печатает строчными; смена распознавателя — и сценарий развалится.
     * Нормализуем здесь, в единственной точке входа, чтобы правило действовало
     * на любую будущую реализацию NluModel.
     *
     * Пунктуацию НЕ трогаем: модель на ней обучена.
     */
    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("model", model.name());
        if (model instanceof OnnxNluModel onnx) {
            status.put("ready", onnx.isReady());
            status.put("error", onnx.initError());
        } else {
            status.put("ready", !"disabled".equals(model.name()));
            status.put("error", null);
        }
        return status;
    }
}
