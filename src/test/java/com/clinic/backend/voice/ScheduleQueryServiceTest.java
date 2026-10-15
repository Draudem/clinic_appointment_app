package com.clinic.backend.voice;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.rag.ClinicalKnowledgeBase;
import com.clinic.backend.schedule.ScheduleEntry;
import com.clinic.backend.schedule.ScheduleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * С-07 «Справка о графике работы».
 *
 * Проверяем две вещи, которые регламент требует жёстко:
 *   1. Вопрос про график НЕ уходит в запись. Это не пожелание: и старая, и
 *      новая модель на части таких фраз отвечали <BOOK>, а пациент,
 *      спросивший «работает ли сегодня хирург», получал предложение записаться.
 *   2. Там, где данных нет, ассистент переводит оператору, а не выдумывает
 *      (регламент §3.8 и §5 «не обещают того, чего нет в справочниках»).
 */
class ScheduleQueryServiceTest {

    private static final LocalDate TODAY = LocalDate.now();

    /** surgeons: JS-дни 3 и 5 = среда и пятница. */
    private static Doctor surgeon() {
        Doctor d = new Doctor("Соколов Игорь Юрьевич", "Хирург", "09:30", List.of(3, 5));
        return d;
    }

    private static ScheduleQueryService service(DoctorRepository repo) {
        return new ScheduleQueryService(repo, mock(ScheduleProvider.class));
    }

    private static ScheduleQueryService withDoctors(Doctor... ds) {
        DoctorRepository repo = mock(DoctorRepository.class);
        when(repo.findAll()).thenReturn(List.of(ds));
        return service(repo);
    }

    private static String answer(ScheduleQueryService s, String text) {
        Optional<ScheduleInfoRule.ScheduleInfo> info = ScheduleInfoRule.detect(text);
        assertTrue(info.isPresent(), "фраза должна распознаваться как вопрос о графике: " + text);
        return s.answer(info.get()).orElseThrow().text();
    }

    // ------------------------------------------------------------------
    // 1. График не должен уходить в запись
    // ------------------------------------------------------------------

    @Test
    @DisplayName("вопросы регламента распознаются как справка о графике, а не как запись")
    void regulationTriggersDetected() {
        for (String phrase : List.of(
                "Когда принимает хирург?",
                "до скольки вы работаете?",
                "работает ли сегодня хирург?",
                "работаете в субботу",
                "Как вы работаете?",
                "В каком кабинете принимает врач?",
                "график работы врачей",
                "режим работы поликлиники")) {
            assertTrue(ScheduleInfoRule.detect(phrase).isPresent(),
                    "не распознан как вопрос о графике: " + phrase);
        }
    }

    @Test
    @DisplayName("«работает ли сегодня хирург» — справка о графике, а не запись")
    void todayQuestionIsNotBooking() {
        // «хирург» в фразе есть, поэтому без правила сработал бы предохранитель
        // намерения и пациента записали бы к хирургу вместо ответа
        Optional<ScheduleInfoRule.ScheduleInfo> info =
                ScheduleInfoRule.detect("работает ли сегодня хирург");
        assertTrue(info.isPresent());
        assertTrue(info.get().today());
        assertEquals("Хирург", info.get().specialty());
        assertNull(info.get().doctorHint(),
                "«хирург» — это специальность, а не фамилия: " + info.get().doctorHint());

        String reply = answer(withDoctors(surgeon()), "работает ли сегодня хирург");
        // предложение записи регламент требует (результат С-07), но запись
        // не должна начинаться сама и тем более требовать идентификацию
        assertFalse(reply.contains("Как вас зовут"), reply);
    }

    @Test
    @DisplayName("фразы записи не перехватываются правилом графика")
    void bookingNotCaptured() {
        for (String phrase : List.of(
                "хочу записаться к стоматологу",
                "запишите меня к терапевту на завтра",
                "перенесите приём на пятницу",
                "отмените мою запись",
                "хочу на прием к кардиологу",
                "у меня много работы на этой неделе")) {
            assertFalse(ScheduleInfoRule.detect(phrase).isPresent(),
                    "фраза записи не должна считаться справкой о графике: " + phrase);
        }
    }

    // ------------------------------------------------------------------
    // 2. Ответы по данным, без домыслов
    // ------------------------------------------------------------------

    @Test
    @DisplayName("«когда принимает хирург» — дни и время из строки doctors")
    void doctorBySpecialty() {
        String reply = answer(withDoctors(surgeon()), "Когда принимает хирург?");
        assertTrue(reply.contains("Соколов Игорь Юрьевич"), reply);
        assertTrue(reply.contains("средам"), reply);
        assertTrue(reply.contains("пятницам"), reply);
        assertTrue(reply.contains("в девять тридцать"), reply);
        assertTrue(reply.endsWith("Записать вас?"),
                "регламент: результат С-07 — «Сведения сообщены; предложена запись»: " + reply);
    }

    @Test
    @DisplayName("врач назван фамилией — отвечаем про этого врача")
    void doctorBySurname() {
        String reply = answer(withDoctors(surgeon()), "Когда принимает Кузнецова?");
        // Кузнецовой в базе нет — по регламенту это «врач не найден»
        assertTrue(reply.startsWith("Такого врача в расписании нет."), reply);
    }

    @Test
    @DisplayName("«как вы работаете» — скрипт регламента §6.1")
    void workingHours() {
        String reply = answer(withDoctors(surgeon()), "Как вы работаете? До скольки?");
        assertTrue(reply.contains("по будням с семи тридцати до двадцати"), reply);
        assertTrue(reply.contains("в субботу с девяти до пятнадцати"), reply);
        assertTrue(reply.contains("В воскресенье выходной"), reply);
    }

    @Test
    @DisplayName("праздники: приказа главного врача у нас нет — перевод оператору")
    void holidaysGoToOperator() {
        Optional<ScheduleInfoRule.ScheduleInfo> info =
                ScheduleInfoRule.detect("Работаете в праздники?");
        assertTrue(info.isPresent());
        ScheduleQueryService.Reply reply = withDoctors(surgeon())
                .answer(info.get()).orElseThrow();
        assertTrue(reply.transfer(), "праздничный режим нельзя выдумывать");
        assertTrue(reply.text().contains("приказом главного врача"), reply.text());
    }

    @Test
    @DisplayName("отпуск врача: причину не называем, заменяющего в базе нет — перевод")
    void vacationGoesToOperator() {
        Optional<ScheduleInfoRule.ScheduleInfo> info =
                ScheduleInfoRule.detect("Соколов на больничном?");
        assertTrue(info.isPresent(), "отсутствие врача — тоже вопрос о графике");
        ScheduleQueryService.Reply reply = withDoctors(surgeon())
                .answer(info.get()).orElseThrow();
        assertTrue(reply.transfer());
        assertFalse(reply.text().toLowerCase().contains("больни"),
                "причину отсутствия называть нельзя (регламент, карточка С-07): " + reply.text());
    }

    @Test
    @DisplayName("база пуста — перевод оператору, а не выдуманный график")
    void emptyDatabase() {
        String reply = answer(withDoctors(), "Когда принимает хирург?");
        assertTrue(reply.contains("Соединяю"), reply);
    }

    // ------------------------------------------------------------------
    // 3. Кабинет: ответ есть в расписании
    // ------------------------------------------------------------------

    @Test
    @DisplayName("кабинет берётся из расписания, этаж не выдумывается")
    void cabinetFromSchedule() {
        DoctorRepository repo = mock(DoctorRepository.class);
        when(repo.findAll()).thenReturn(List.of(surgeon()));
        ScheduleProvider schedule = mock(ScheduleProvider.class);
        when(schedule.byDoctorFio(any(), any(), any())).thenReturn(List.of(
                new ScheduleEntry(1L, "Соколов Игорь Юрьевич", "Хирург", TODAY, "09:30", 112)));
        ScheduleQueryService s = new ScheduleQueryService(repo, schedule);

        String reply = answer(s, "В каком кабинете принимает Соколов?");
        assertTrue(reply.contains("кабинете"), reply);
        assertTrue(reply.contains("сто двенадцать"), reply);
        assertFalse(reply.contains("этаж"),
                "этажа в базе нет — регламентный скрипт целиком не произносится: " + reply);
    }

    @Test
    @DisplayName("кабинет без имени врача: просим назвать врача, а не гадаем")
    void cabinetWithoutDoctor() {
        String reply = answer(withDoctors(surgeon()), "В каком кабинете принимает врач?");
        assertTrue(reply.contains("Назову кабинет"), reply);
        assertFalse(reply.contains("сто двенадцать"),
                "единственного хирурга в примере мы назовём счастливцем: " + reply);
    }

    // ------------------------------------------------------------------
    // 4. Предохранитель намерения
    // ------------------------------------------------------------------

    @Test
    @DisplayName("слово-обозначение врача восстанавливает намерение на коротких ответах")
    void designationRecoversIntent() {
        assertEquals("Терапевт", ClinicalKnowledgeBase.specialtyByDesignation("к терапевту"));
        assertEquals("Стоматолог", ClinicalKnowledgeBase.specialtyByDesignation("к стоматологу"));
        assertEquals("ЛОР", ClinicalKnowledgeBase.specialtyByDesignation("к лору"));
        assertEquals("Стоматолог", ClinicalKnowledgeBase.specialtyByDesignation("к дантисту"));
        assertEquals("Стоматолог", ClinicalKnowledgeBase.specialtyByDesignation("к зубному"));
        assertEquals("Офтальмолог", ClinicalKnowledgeBase.specialtyByDesignation("к окулисту"));
        assertEquals("Дерматолог", ClinicalKnowledgeBase.specialtyByDesignation("к кожнику"));
    }

    @Test
    @DisplayName("симптом и посторонняя тема не считаются обозначением врача")
    void symptomsAreNotDesignations() {
        assertNull(ClinicalKnowledgeBase.specialtyByDesignation("у меня кашель и температура"));
        assertNull(ClinicalKnowledgeBase.specialtyByDesignation("какая завтра погода в москве"));
        assertNull(ClinicalKnowledgeBase.specialtyByDesignation("закажи пиццу на вечер"));
        assertNull(ClinicalKnowledgeBase.specialtyByDesignation("да"));
        assertNull(ClinicalKnowledgeBase.specialtyByDesignation("работаете в субботу"));
    }
}
