package com.clinic.backend.voice;

import com.clinic.backend.nlu.NluFrame;
import com.clinic.backend.nlu.NluService;
import com.clinic.backend.rag.ClinicalKnowledgeBase;

import java.util.ArrayList;
import java.util.List;

/**
 * Парсер команд на локальной ONNX-модели. Токены специальности модели
 * переводятся в названия из БД; если модель вернула UNKNOWN_SPECIALTY
 * (частый случай для разговорных синонимов: «дантист», «окулист»),
 * специальность добирается из RAG-словаря по исходному тексту.
 */
public class NluCommandParser implements CommandParser {

    private final NluService nlu;
    private final ClinicalKnowledgeBase knowledgeBase;

    public NluCommandParser(NluService nlu, ClinicalKnowledgeBase knowledgeBase) {
        this.nlu = nlu;
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public String name() {
        return "nlu";
    }

    @Override
    public ParsedCommand parse(String text) {
        NluFrame frame = nlu.parse(text);
        String specialty = ClinicalKnowledgeBase.russianNameForNluToken(frame.specialty());
        String engine = "nlu";
        if (specialty == null && !frame.unsupported()) {
            String fromRag = knowledgeBase.recommendSpecialty(text);
            if (fromRag != null) {
                specialty = fromRag;
                engine = "nlu+rag";
            }
        }
        List<String> symptoms = new ArrayList<>();
        for (String s : frame.symptoms()) {
            symptoms.add(s.replace("<SYMPTOM_", "").replace(">", "").toLowerCase());
        }
        return new ParsedCommand(
                frame.intent(),
                specialty,
                frame.timeFactors(),
                symptoms,
                frame.timeUnknown(),
                frame.needsClarification(),
                frame.unsupported(),
                frame.frame(),
                engine);
    }
}
