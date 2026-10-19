package com.clinic.backend.nlu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FrameVocab {

    private FrameVocab() {
    }

    /** Все интенты (из INTENT_TOKENS и ANSWER_TOKENS). */
    public static final List<String> INTENTS = List.of(
            "<BOOK>", "<RESCHEDULE>", "<CANCEL>", "<CHECK_BOOKING>",
            "<HOME_VISIT>", "<SCHEDULE>", "<LAB_STATUS>", "<PAID>",
            "<WAITLIST>", "<DOCS>", "<COMPLAINT>", "<MEDICAL>",
            "<UNSUPPORTED>", "<YES>", "<NO>"
    );

    public static final Set<String> BARE_INTENTS = Set.of("<UNSUPPORTED>", "<YES>", "<NO>");

    public static final List<String> BUSINESS_INTENTS = INTENTS.stream()
            .filter(t -> !t.equals("<YES>") && !t.equals("<NO>"))
            .toList();

    /** Специальности (из SPECIALTIES). */
    public static final List<String> SPECIALTIES = List.of(
            "<GP>", "<PEDIATRICIAN>", "<DENTIST>", "<DERMATOLOGIST>",
            "<GYNECOLOGIST>", "<OPHTHALMOLOGIST>", "<NEUROLOGIST>",
            "<PSYCHOLOGIST>", "<SURGEON>", "<CARDIOLOGIST>", "<UROLOGIST>",
            "<ENDOCRINOLOGIST>", "<ORTHOPEDIST>", "<ENT>", "<UNKNOWN_SPECIALTY>"
    );

    /** Симптомы (из SYMPTOMS). Обратите внимание на <SYMPTOM_COUGH> (было <SYMPTOM_COGH>). */
    public static final List<String> SYMPTOMS = List.of(
            "<SYMPTOM_TOOTHACHE>", "<SYMPTOM_SORE_THROAT>", "<SYMPTOM_FEVER>",
            "<SYMPTOM_RASH>", "<SYMPTOM_BACK_PAIN>", "<SYMPTOM_HEADACHE>",
            "<SYMPTOM_EYE_PAIN>", "<SYMPTOM_ANXIETY>", "<SYMPTOM_COUGH>",
            "<SYMPTOM_STOMACH_PAIN>", "<SYMPTOM_DIZZINESS>", "<SYMPTOM_INSOMNIA>",
            "<SYMPTOM_ALLERGY>", "<SYMPTOM_CHEST_PAIN>", "<SYMPTOM_SHORTNESS_OF_BREATH>",
            "<SYMPTOM_LOSS_OF_CONSCIOUSNESS>", "<SYMPTOM_NAUSEA>",
            "<SYMPTOM_VOMITING>", "<SYMPTOM_JOINT_PAIN>"
    );

    /** Служебные токены (включая T_AMBIG из новой модели). */
    public static final List<String> SERVICE_TOKENS = List.of(
            "<T_UNKNOWN>", "<TIME>", "</TIME>", "<NEEDS_CLARIFICATION>",
            "<END_FRAME>", "<EOS>", "T_AMBIG"
    );

    /** Область времени. */
    public static final Set<String> TIME_SCOPE = Set.of(
            "T_THIS_WEEK", "T_NEXT_WEEK", "T_WEEKEND", "T_THIS_MONTH", "T_NEXT_MONTH"
    );

    /** День. */
    public static final Set<String> TIME_DAY = Set.of(
            "T_TODAY", "T_TOMORROW", "T_DAY_AFTER_TOMORROW", "T_IN_2_DAYS",
            "T_IN_3_DAYS", "T_IN_WEEK", "T_IN_2_WEEKS", "T_IN_MONTH",
            "T_MON", "T_TUE", "T_WED", "T_THU", "T_FRI", "T_SAT", "T_SUN"
    );

    /** Часть суток. */
    public static final Set<String> TIME_OF_DAY = buildTimeOfDay();

    private static Set<String> buildTimeOfDay() {
        Set<String> set = new LinkedHashSet<>(List.of(
                "T_MORNING", "T_AFTERNOON", "T_EVENING", "T_EARLY",
                "T_BEFORE_NOON", "T_AFTER_NOON", "T_AFTER_18", "T_AFTER_WORK",
                "T_AFTER_SHIFT", "T_SOON"
        ));
        for (int h = 8; h <= 20; h++) {
            for (int m = 0; m <= 45; m += 15) {
                set.add(String.format("T_H%02d_%02d", h, m));
            }
        }
        return Set.copyOf(set);
    }

    public static int rank(String token) {
        if (TIME_SCOPE.contains(token)) return 0;
        if (TIME_DAY.contains(token)) return 1;
        return 2; // TIME_OF_DAY и слоты
    }

    public static List<String> allFrameTokens() {
        List<String> all = new ArrayList<>();
        all.addAll(INTENTS);
        all.addAll(SPECIALTIES);
        all.addAll(SYMPTOMS);
        all.addAll(SERVICE_TOKENS);
        all.addAll(TIME_SCOPE);
        all.addAll(TIME_DAY);
        all.addAll(TIME_OF_DAY);
        return all;
    }

    public static List<String> unsupportedTimeTokens(Set<String> modelVocabulary) {
        List<String> missing = new ArrayList<>();
        for (String t : modelVocabulary) {
            if (t.startsWith("T_") && !TIME_SCOPE.contains(t)
                    && !TIME_DAY.contains(t) && !TIME_OF_DAY.contains(t)
                    && !t.equals("T_UNKNOWN") && !t.equals("T_AMBIG")) {
                missing.add(t);
            }
        }
        java.util.Collections.sort(missing);
        return missing;
    }
}