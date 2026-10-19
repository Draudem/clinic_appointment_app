package com.clinic.backend.voice;

import java.util.List;

/**
 * Результат одного хода диалога: что сказать пациенту и что с этим делать.
 *
 * @param reply          что произнести
 * @param action         что сделать с звонком
 * @param stage          этап сценария после хода
 * @param options        предложенные слоты, если применимо
 * @param rule           код правила маршрутизации R-xx, если был перевод
 * @param resultCode     код результата звонка (Приложение Г)
 * @param miss           счётчик непониманий для лестницы R-05
 * @param transferReason объяснение причины перевода (RED_FLAG, OPERATOR_REQUEST и т.д.)
 * @param confidence     уверенность ассистента в результате (0.0 - 1.0)
 */
public record Turn(String reply, Action action, String stage, List<Slot> options,
                   String rule, String resultCode, int miss,
                   String transferReason, double confidence) {

    /** Что делать с соединением после фразы. */
    public enum Action {
        /** Слушаем дальше. */
        CONTINUE,
        /** Сценарий завершён, можно вешать трубку. */
        HANGUP,
        /** Перевод сотруднику — дальше говорит человек. */
        TRANSFER
    }

    /** Слот в том виде, в котором его видит сценарий и озвучка. */
    public record Slot(Long doctorId, String fio, String specialty,
                       java.time.LocalDate date, String time, Integer cabinet) {

        /** «в четверг, первого октября, в девять тридцать, кабинет двести четырнадцать» */
        public String spoken() {
            String base = RussianTime.sayDateTime(date, time);
            return cabinet == null ? base : base + ", кабинет " + RussianTime.number(cabinet);
        }

        /** Короткая форма для списка: «в четверг в девять тридцать» */
        public String spokenShort() {
            return RussianTime.sayDate(date) + " " + RussianTime.sayTime(time);
        }

        /** Компактная форма для перечня вариантов. */
        public String spokenCompact(java.time.LocalDate today) {
            String when = date.isAfter(today.plusDays(7))
                    ? RussianTime.sayDayAndMonth(date)
                    : "в " + RussianTime.weekdayAccusative(date);
            return when + " " + RussianTime.sayTime(time);
        }
    }

    // --- Статические фабрики (сохраняем обратную совместимость) ---

    public static Turn continueWith(String reply, String stage, List<Slot> options, int miss) {
        return new Turn(reply, Action.CONTINUE, stage, options, null, null, miss, null, 0.75);
    }

    public static Turn hangup(String reply, String stage, String resultCode, int miss) {
        double conf = "BOOKED".equals(resultCode) || "RESCHEDULED".equals(resultCode) ? 0.95 : 0.9;
        return new Turn(reply, Action.HANGUP, stage, List.of(), null, resultCode, miss, null, conf);
    }

    public static Turn transfer(String reply, String rule, String resultCode, int miss) {
        return new Turn(reply, Action.TRANSFER, "OPERATOR", List.of(), rule, resultCode, miss, rule, 0.5);
    }

    /**
     * Новый метод для переводов с явной причиной (для логов и UI).
     */
    public static Turn transferWithReason(String reply, String rule, String resultCode, int miss, String reason) {
        return new Turn(reply, Action.TRANSFER, "OPERATOR", List.of(), rule, resultCode, miss, reason, 0.5);
    }
}