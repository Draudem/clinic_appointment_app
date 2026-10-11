package com.clinic.backend.nlu;

import java.util.List;
import java.util.Set;

/**
 * Закрытая лексика фрейма (единый источник истины — synth/vocab.py у коллеги).
 * Токены автомата: специальности/симптомы/время имеют ровно эти имена.
 */
public final class FrameVocab {

    private FrameVocab() {
    }

    public static final List<String> INTENTS = List.of(
            "<BOOK>", "<RESCHEDULE>", "<CANCEL>", "<UNSUPPORTED>");

    public static final List<String> SPECIALTIES = List.of(
            "<GP>", "<DENTIST>", "<DERMATOLOGIST>", "<GYNECOLOGIST>",
            "<OPHTHALMOLOGIST>", "<NEUROLOGIST>", "<PSYCHOLOGIST>",
            "<SURGEON>", "<ENT>", "<UNKNOWN_SPECIALTY>");

    public static final List<String> SYMPTOMS = List.of(
            "<SYMPTOM_TOOTHACHE>", "<SYMPTOM_SORE_THROAT>", "<SYMPTOM_FEVER>",
            "<SYMPTOM_RASH>", "<SYMPTOM_BACK_PAIN>", "<SYMPTOM_HEADACHE>",
            "<SYMPTOM_EYE_PAIN>", "<SYMPTOM_ANXIETY>", "<SYMPTOM_COUGH>",
            "<SYMPTOM_STOMACH_PAIN>", "<SYMPTOM_DIZZINESS>",
            "<SYMPTOM_INSOMNIA>", "<SYMPTOM_ALLERGY>");

    /** Категории времени: ранг растёт ОБЛАСТЬ(0) -> ДЕНЬ(1) -> ВРЕМЯ(2). */
    public static final Set<String> TIME_SCOPE = Set.of(
            "T_NEXT_MONTH", "T_NEXT_WEEK", "T_THIS_MONTH", "T_THIS_WEEK", "T_WEEKEND");

    public static final Set<String> TIME_DAY = Set.of(
            "T_DAY_AFTER_TOMORROW", "T_FRI", "T_IN_2_DAYS", "T_IN_2_WEEKS",
            "T_IN_3_DAYS", "T_IN_MONTH", "T_IN_WEEK", "T_MON", "T_SAT", "T_SUN",
            "T_THU", "T_TODAY", "T_TOMORROW", "T_TUE", "T_WED");

    public static final Set<String> TIME_OF_DAY = Set.of(
            "T_AFTERNOON", "T_AFTER_18", "T_AFTER_NOON", "T_AFTER_SHIFT",
            "T_AFTER_WORK", "T_BEFORE_NOON", "T_EARLY", "T_EVENING",
            "T_H08_00", "T_H08_30", "T_H09_00", "T_H09_30", "T_H10_00",
            "T_H11_00", "T_H13_00", "T_H14_00", "T_H15_00", "T_H16_00",
            "T_H17_00", "T_H17_30", "T_H18_00", "T_H18_30", "T_H19_00",
            "T_H20_00", "T_H21_00", "T_H22_00", "T_MIDDAY", "T_MORNING", "T_SOON");

    static int rank(String token) {
        if (TIME_SCOPE.contains(token)) {
            return 0;
        }
        if (TIME_DAY.contains(token)) {
            return 1;
        }
        return 2;
    }
}
