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

        String q = ClinicalKnowledgeBase.normalize(text);
        // Предохранитель для переноса
        if (q.contains("перенес") || q.contains("перезапиш") || q.contains("поменяй") || q.contains("сдвин")) {
            intent = "RESCHEDULE";
        }

        // Предохранитель для справки о графике (С-07)
        if (q.contains("график") || q.contains("расписание") || q.contains("режим работы")
                || q.contains("во сколько") || q.contains("до скольки") || q.contains("кабинет")
                || q.contains("этаж") || q.contains("адрес")) {
            intent = "SCHEDULE";
        }

        // Предохранитель для анализов (С-08)
        if (q.contains("анализ") || q.contains("результат") || q.contains("кровь готова") || q.contains("гормоны готовы")) {
            intent = "LAB_STATUS";
        }
        // Предохранитель для отмены (С-04)
        else if (q.contains("отмен") || q.contains("снимите") || q.contains("не приду") || q.contains("удалите запись")) {
            intent = "CANCEL";
        }
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
