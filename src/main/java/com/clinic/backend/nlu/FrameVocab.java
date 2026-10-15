package com.clinic.backend.nlu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Закрытая лексика фрейма. Единый источник истины — словарь модели
 * {@code tokenizer_v2.json} у коллеги (13 интентов телефонического каталога
 * регламента РГ-ТЕЛ-01), а список поддерживаемых токенов выводится из него
 * при старте (см. {@link #unsupportedTimeTokens}).
 *
 * Токены автомата: специальности/симптомы/время имеют ровно эти имена.
 *
 * ВАЖНО про время. Модель выдаёт сетку слотов с шагом 15 минут
 * (T_H08_00 … T_H20_45) и маркер неоднозначного времени T_AMBIG.
 * Если какой-то токен есть в модели, но отсутствует здесь, он не попадает
 * в allowed() — и модель вынуждена подставить другой, то есть время «едет».
 * Поэтому список должен точно совпадать со словарём модели.
 *
 * ВАЖНО про парность. model.onnx и токенизатор — связанная пара: веса привязаны
 * к номерам токенов. При этом номера почти всех токенов между старым
 * tokenizer.json (4 интента) и новым tokenizer_v2.json (13) разошлись —
 * совпали лишь 15 служебных. Поменять модель, но оставить старый токенизатор
 * значит молча кодировать и декодировать не те токены: без исключения, просто
 * «модель стала глупее». Поэтому пути задаются рядом и меняются вместе.
 */
public final class FrameVocab {

    private FrameVocab() {
    }

    /** Все интенты каталога телефонии (раздел 4 регламента). */
    public static final List<String> INTENTS = List.of(
            "<BOOK>",                    // I-01 запись на приём
            "<RESCHEDULE>",              // I-02 перенос записи
            "<CANCEL>",                  // I-03 отмена записи
            "<CHECK_BOOKING>",           // I-04 проверка своей записи
            "<HOME_VISIT>",              // I-05 вызов врача на дом
            "<SCHEDULE>",                // С-07 справка о графике работы
            "<LAB_STATUS>",              // С-08 готовность анализов
            "<PAID>",                    // С-09 платные услуги
            "<WAITLIST>",                // I-09 лист ожидания
            "<DOCS>",                    // справки, документы, прикрепление
            "<COMPLAINT>",               // С-15 жалоба
            "<MEDICAL>",                 // медицинский вопрос (граница 3.8)
            "<UNSUPPORTED>",
            "<YES>",                     // голое «да» — раньше разбиралось вручную
            "<NO>");                     // голое «нет»

    /**
     * Интенты без слотов: за ними сразу идёт конец фрейма.
     *
     * Модель сама научилась отвечать на голое «да» и «нет» — раньше это была
     * наша забота. Обрабатываем их так же, как UNSUPPORTED, иначе автомат
     * заставил бы модель назвать специальность, которой в ответе нет.
     */
    public static final Set<String> BARE_INTENTS = Set.of(
            "<UNSUPPORTED>", "<YES>", "<NO>");

    /** 13 интентов каталога без служебных — для диагностики. */
    public static final List<String> BUSINESS_INTENTS = INTENTS.stream()
            .filter(t -> !t.equals("<YES>") && !t.equals("<NO>"))
            .toList();

    public static final List<String> SPECIALTIES = List.of(
            "<GP>", "<DENTIST>", "<DERMATOLOGIST>", "<GYNECOLOGIST>",
            "<OPHTHALMOLOGIST>", "<NEUROLOGIST>", "<PSYCHOLOGIST>",
            "<SURGEON>", "<ENT>", "<UNKNOWN_SPECIALTY>");

    public static final List<String> SYMPTOMS = List.of(
            "<SYMPTOM_TOOTHACHE>", "<SYMPTOM_SORE_THROAT>", "<SYMPTOM_FEVER>",
            "<SYMPTOM_RASH>", "<SYMPTOM_BACK_PAIN>", "<SYMPTOM_HEADACHE>",
            "<SYMPTOM_EYE_PAIN>", "<SYMPTOM_ANXIETY>", "<SYMPTOM_COGH>",
            "<SYMPTOM_STOMACH_PAIN>", "<SYMPTOM_DIZZINESS>",
            "<SYMPTOM_INSOMNIA>", "<SYMPTOM_ALLERGY>");

    /** Служебные токены вне списков времени. */
    public static final List<String> SERVICE_TOKENS = List.of(
            "<T_UNKNOWN>", "<TIME>", "</TIME>", "<NEEDS_CLARIFICATION>",
            "<END_FRAME>", "<EOS>");

    /** Область: ранг 0 — самый «широкий» уровень времени. */
    public static final Set<String> TIME_SCOPE = Set.of(
            "T_NEXT_MONTH", "T_NEXT_WEEK", "T_THIS_MONTH", "T_THIS_WEEK", "T_WEEKEND");

    /** День: ранг 1. */
    public static final Set<String> TIME_DAY = Set.of(
            "T_TODAY", "T_TOMORROW", "T_DAY_AFTER_TOMORROW",
            "T_IN_2_DAYS", "T_IN_3_DAYS", "T_IN_WEEK", "T_IN_2_WEEKS", "T_IN_MONTH",
            "T_MON", "T_TUE", "T_WED", "T_THU", "T_FRI", "T_SAT", "T_SUN");

    /** Часть суток: ранг 2. */
    public static final Set<String> TIME_OF_DAY = buildTimeOfDay();

    /**
     * Часть суток = именованные интервалы + сетка слотов с шагом 15 минут.
     * Сетка генерируется, а не перечисляется списком: в модели 51 значение
     * T_H*, и любое рассинхронизация со словарём молча ломает разбор времени.
     */
    private static Set<String> buildTimeOfDay() {
        Set<String> set = new LinkedHashSet<>(List.of(
                "T_MORNING", "T_EARLY", "T_BEFORE_NOON",
                "T_MIDDAY", "T_AFTERNOON", "T_AFTER_NOON",
                "T_AFTER_WORK", "T_AFTER_SHIFT", "T_EVENING", "T_AFTER_18",
                "T_SOON",
                // «время названо, но однозначно не разрешить» — модель ставит его,
                // когда слот есть, а конкретное время выбрать не из чего
                "T_AMBIG"));
        for (int h = 8; h <= 20; h++) {
            for (int m = 0; m <= 45; m += 15) {
                set.add(String.format("T_H%02d_%02d", h, m));
            }
        }
        return Set.copyOf(set);
    }

    /** Ранг времени: ранг растёт ОБЛАСТЬ(0) -> ДЕНЬ(1) -> ВРЕМЯ(2). */
    public static int rank(String token) {
        if (TIME_SCOPE.contains(token)) {
            return 0;
        }
        if (TIME_DAY.contains(token)) {
            return 1;
        }
        return 2;
    }

    /** Все токены, которые автомат вообще умеет принимать. */
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

    /**
     * Диагностика совместимости словаря: какие токены времени есть в модели,
     * но не поддерживаются автоматом. Непустой результат означает, что модель
     * будет подставлять другое время вместо названного пациентом.
     */
    public static List<String> unsupportedTimeTokens(Set<String> modelVocabulary) {
        List<String> missing = new ArrayList<>();
        for (String t : modelVocabulary) {
            if (t.startsWith("T_") && !TIME_SCOPE.contains(t)
                    && !TIME_DAY.contains(t) && !TIME_OF_DAY.contains(t)) {
                missing.add(t);
            }
        }
        java.util.Collections.sort(missing);
        return missing;
    }
}