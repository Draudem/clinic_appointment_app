package com.clinic.backend.config;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.specialty.Specialty;
import com.clinic.backend.specialty.SpecialtyRepository;
import com.clinic.backend.user.Role;
import com.clinic.backend.user.User;
import com.clinic.backend.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * Начальные данные: администратор для демо-доступа фронтенда (admin/admin123),
 * справочник специальностей и расписание врачей. Идемпотентно — при перезапуске
 * данные не дублируются. Время приёма врачей уникально (контракт
 * /api/doctors/unique-times).
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final List<Integer> WEEKDAYS = List.of(1, 2, 3, 4, 5);
    private static final List<Integer> ODD_DAYS = List.of(1, 3, 5);
    private static final List<Integer> EVEN_DAYS = List.of(2, 4);

    @Bean
    ApplicationRunner seedAdmin(UserRepository users, PasswordEncoder encoder) {
        return args -> {
            if (!users.existsByLogin("admin")) {
                // Демо-администратор: имя/фамилия теперь обязательные поля
                users.save(new User("admin", encoder.encode("admin123"), Role.ADMIN,
                        "Иван", "Админов"));
                log.info("Создан администратор admin/admin123 (Иван Админов)");
            }
        };
    }

    @Bean
    ApplicationRunner seedCatalog(SpecialtyRepository specialties, DoctorRepository doctors) {
        return args -> {
            if (specialties.count() == 0) {
                specialties.saveAll(List.of(
                        new Specialty("Терапевт", List.of("ОРВИ", "простуда", "кашель", "насморк",
                                "температура", "слабость", "гастрит", "анемия")),
                        new Specialty("Кардиолог", List.of("гипертония", "аритмия", "боль в груди",
                                "одышка", "инфаркт", "холестерин")),
                        new Specialty("Невролог", List.of("мигрень", "головная боль", "остеохондроз",
                                "головокружение", "онемение", "бессонница")),
                        new Specialty("Хирург", List.of("грыжа", "аппендицит", "варикоз",
                                "холецистит", "нарыв")),
                        new Specialty("Офтальмолог", List.of("конъюнктивит", "близорукость",
                                "глаукома", "катаракта", "косоглазие")),
                        new Specialty("ЛОР", List.of("синусит", "отит", "тонзиллит", "ринит",
                                "ангина")),
                        new Specialty("Дерматолог", List.of("дерматит", "псориаз", "экзема",
                                "акне", "кандидоз")),
                        new Specialty("Эндокринолог", List.of("диабет", "зоб", "ожирение",
                                "гипотиреоз")),
                        new Specialty("Гинеколог", List.of("миома", "эндометриоз", "цистит",
                                "беременность")),
                        new Specialty("Уролог", List.of("цистит", "простатит", "пиелонефрит",
                                "мочекаменная болезнь")),
                        new Specialty("Педиатр", List.of("детские инфекции", "простуда",
                                "вакцинация")),
                        new Specialty("Ортопед", List.of("артроз", "сколиоз", "плоскостопие",
                                "перелом", "травма")),
                        new Specialty("Стоматолог", List.of("кариес", "пульпит", "гингивит",
                                "зубная боль")),
                        new Specialty("Психолог", List.of("тревога", "паника", "стресс",
                                "депрессия", "выгорание"))));
                log.info("Загружен справочник специальностей: {}", specialties.count());
            }

            if (doctors.count() == 0) {
                doctors.saveAll(List.of(
                        new Doctor("Иванов Пётр Сергеевич", "Терапевт", "09:00", ODD_DAYS),
                        new Doctor("Попова Елена Игоревна", "Терапевт", "10:30", EVEN_DAYS),
                        new Doctor("Смирнов Алексей Викторович", "Стоматолог", "11:00", WEEKDAYS),
                        new Doctor("Кузнецова Анна Павловна", "Дерматолог", "12:30", ODD_DAYS),
                        new Doctor("Васильев Дмитрий Олегович", "Невролог", "14:00", EVEN_DAYS),
                        new Doctor("Морозова Ольга Ивановна", "ЛОР", "15:30", ODD_DAYS),
                        new Doctor("Новиков Сергей Петрович", "Офтальмолог", "16:00",
                                List.of(2, 4, 6)),
                        new Doctor("Фёдорова Мария Андреевна", "Кардиолог", "08:30",
                                List.of(1, 4)),
                        new Doctor("Соколов Игорь Юрьевич", "Хирург", "09:30", List.of(3, 5)),
                        new Doctor("Лебедева Татьяна Валерьевна", "Психолог", "17:00", WEEKDAYS),
                        new Doctor("Григорьев Павел Дмитриевич", "Ортопед", "13:00",
                                List.of(2, 5)),
                        new Doctor("Егорова Наталья Сергеевна", "Эндокринолог", "11:30",
                                List.of(1, 3)),
                        new Doctor("Романов Андрей Игоревич", "Уролог", "18:00", EVEN_DAYS),
                        new Doctor("Захарова Ирина Петровна", "Педиатр", "10:00", WEEKDAYS)));
                log.info("Загружено расписание врачей: {}", doctors.count());
            }
        };
    }
}
