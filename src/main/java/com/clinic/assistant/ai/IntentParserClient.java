package com.clinic.assistant.ai;

import com.clinic.assistant.ai.frame.VoiceFrame;

/**
 * ПОРТ (интерфейс) этапа 2 конвейера: разбор текста в структурированный фрейм.
 *
 * <p>Модель (см. models/model.onnx + models/tokenizer.json) возвращает фрейм вида:</p>
 * <pre>{@code <BOOK> <NEUROLOGIST> <T_TOMORROW> <T_H10_00> <END_FRAME>}</pre>
 *
 * <p>Реализации переключаются свойством {@code app.ai.parser.provider}:</p>
 * <ul>
 *   <li>{@code StubIntentParserClient} — заглушка на правилах/словарях;</li>
 *   <li>{@code HttpIntentParserClient} — HTTP-запрос к сервису, в котором
 *       развёрнута ONNX-модель парсера.</li>
 * </ul>
 */
public interface IntentParserClient {

    /** @return разобранный фрейм намерения пользователя. */
    VoiceFrame parse(String text);
}
