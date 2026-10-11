package com.clinic.backend.nlu;

import java.util.List;

/**
 * Разобранный semantic frame (результат NLU-модели):
 *   "<BOOK> <DENTIST> <TIME> T_TOMORROW T_MORNING </TIME> <SYMPTOM_HEADACHE> <END_FRAME>"
 */
public record NluFrame(
        String frame,               // каноническая строка токенов
        String intent,              // BOOK | RESCHEDULE | CANCEL | UNSUPPORTED | null
        String specialty,           // DENTIST | ... | UNKNOWN_SPECIALTY | null
        List<String> timeFactors,   // факторы внутри <TIME>...</TIME>
        boolean timeUnknown,        // был <T_UNKNOWN>
        List<String> symptoms,      // <SYMPTOM_*>
        boolean needsClarification, // был <NEEDS_CLARIFICATION>
        boolean unsupported) {      // intent = <UNSUPPORTED>

    public static NluFrame unsupportedFrame() {
        return new NluFrame("<UNSUPPORTED> <END_FRAME>", "UNSUPPORTED", null,
                List.of(), false, List.of(), false, true);
    }

    public static NluFrame parse(String tokens) {
        List<String> list = List.of(tokens.trim().split("\\s+"));
        if (list.isEmpty() || list.get(0).isBlank()) {
            return unsupportedFrame();
        }
        boolean unsupported = list.contains("<UNSUPPORTED>");
        String intent = null;
        for (String t : list) {
            if (FrameVocab.INTENTS.contains(t)) {
                intent = t.replace("<", "").replace(">", "");
                break;
            }
        }
        String specialty = null;
        for (String t : list) {
            if (FrameVocab.SPECIALTIES.contains(t)) {
                specialty = t.replace("<", "").replace(">", "");
                break;
            }
        }
        List<String> timeFactors = new java.util.ArrayList<>();
        boolean inTime = false;
        for (String t : list) {
            if (t.equals("<TIME>")) {
                inTime = true;
                continue;
            }
            if (t.equals("</TIME>")) {
                inTime = false;
                continue;
            }
            if (inTime) {
                timeFactors.add(t);
            }
        }
        boolean timeUnknown = list.contains("<T_UNKNOWN>");
        List<String> symptoms = list.stream()
                .filter(t -> t.startsWith("<SYMPTOM_"))
                .toList();
        boolean needsClarification = list.contains("<NEEDS_CLARIFICATION>");
        return new NluFrame(tokens, intent, specialty, timeFactors, timeUnknown,
                symptoms, needsClarification, unsupported);
    }
}
