package com.clinic.backend.nlu;

import org.springframework.stereotype.Service;

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
        return model.parse(text);
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
