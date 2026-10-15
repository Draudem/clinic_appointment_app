package com.clinic.backend.voice;

import java.util.List;

/**
 * Результат одного хода диалога: что сказать пациенту и что с этим делать.
 *
 * Одна структура на все входы — HTTP (/api/voice/command) и телефон
 * (AudioSocket). Благодаря этому телефонный путь не содержит собственной логики
 * сценария: он только переводит речь в текст, а этот тип — в речь обратно.
 *
 * @param reply      что произнести (регламент: не более 20 слов, не более 3 вариантов)
 * @param action     что сделать с звонком
 * @param stage      этап сценария после хода
 * @param options    предложенные слоты, если применимо
 * @param rule       код правила маршрутизации R-xx, если был перевод
 * @param resultCode код результата звонка (Приложение Г)
 * @param miss       счётчик непониманий для лестницы R-05
 */
public record Turn(String reply, Action action, String stage, List<Slot> options,
                   String rule, String resultCode, int miss) {

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

        /**
         * Компактная форма для ПЕРЕЧНЯ вариантов.
         *
         * Регламент 3.2 ограничивает реплику 20 словами, а в трубке 14 секунд
         * монотонного перечисления — это уже почти целый сценарий и пациент не может
         * перебить. Поэтому в списке дата сокращается: на ближайшей неделе хватает
         * дня недели, дальше — числа и месяца. Полную дату ассистент произносит при
         * подтверждении, там есть где остановиться и переспросить.
         */
        public String spokenCompact(java.time.LocalDate today) {
            // Падежи здесь принципиальны, иначе синтез говорит
            // «в пятница в девять тридцать» и «в двадцать третьего октября».
            // Ближайшая неделя — «в пятницу», дальше «двадцать третьего октября»
            // (предлога «в» здесь не нужно: числительное родительного падежа).
            // Префикс «в » не ставится ещё раз, потому что sayTime() его уже
            // содержит: «в девять тридцать».
            String when = date.isAfter(today.plusDays(7))
                    ? RussianTime.sayDayAndMonth(date)
                    : "в " + RussianTime.weekdayAccusative(date);
            return when + " " + RussianTime.sayTime(time);
        }
    }

    public static Turn continueWith(String reply, String stage, List<Slot> options, int miss) {
        return new Turn(reply, Action.CONTINUE, stage, options, null, null, miss);
    }

    public static Turn hangup(String reply, String stage, String resultCode, int miss) {
        return new Turn(reply, Action.HANGUP, stage, List.of(), null, resultCode, miss);
    }

    public static Turn transfer(String reply, String rule, String resultCode, int miss) {
        return new Turn(reply, Action.TRANSFER, "OPERATOR", List.of(), rule, resultCode, miss);
    }
}