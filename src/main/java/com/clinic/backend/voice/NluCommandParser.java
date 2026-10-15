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

        String intent = frame.intent();
        boolean unsupported = frame.unsupported();

        // --- предохранитель намерения ------------------------------------
        // Замеренная регрессия новой модели: на короткие ответы врачу —
        // ровно то, что пациент говорит в ответ на вопрос ассистента —
        // она отвечает <UNSUPPORTED>: «к терапевту», «к стоматологу»,
        // «к лору», «к зубному». Старая модель такие фразы разбирала.
        // Без этого сценарий записи зацикливается: ассистент спрашивает
        // специальность, пациент отвечает «к лору», ассистент спрашивает
        // снова — до лестницы R-05 и перевода оператору.
        // Спасает слово, которым пациент НАЗВАЛ врача. Только такое:
        //   «какая завтра погода», «закажи пиццу»  — врача не назвали, мимо
        //   «работает ли сегодня хирург»           — вопрос о графике,
        //     его раньше перехватывает ScheduleInfoRule, сюда не доходит
        if (unsupported) {
            String byName = ClinicalKnowledgeBase.specialtyByDesignation(text);
            if (byName != null) {
                specialty = byName;
                intent = "<BOOK>";
                unsupported = false;
                engine = "nlu+предохранитель";
            }
        }

        List<String> symptoms = new ArrayList<>();
        for (String s : frame.symptoms()) {
            symptoms.add(s.replace("<SYMPTOM_", "").replace(">", "").toLowerCase());
        }
        return new ParsedCommand(
                intent,
                specialty,
                frame.timeFactors(),
                symptoms,
                frame.timeUnknown(),
                frame.needsClarification(),
                unsupported,
                frame.frame(),
                engine);
    }
}
