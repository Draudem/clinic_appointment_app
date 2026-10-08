package com.clinic.assistant.seed;

import com.clinic.assistant.clinic.Doctor;
import com.clinic.assistant.clinic.DoctorRepository;
import com.clinic.assistant.clinic.ScheduleSlot;
import com.clinic.assistant.clinic.ScheduleSlotRepository;
import com.clinic.assistant.clinic.Specialty;
import com.clinic.assistant.clinic.SpecialtyRepository;
import com.clinic.assistant.user.Role;
import com.clinic.assistant.user.User;
import com.clinic.assistant.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Демо-данные: врачи, специальности, расписание и пользователи.
 *
 * <p>Выполняется только при пустой БД ({@code app.seed.enabled=true}).
 * Данные совпадают с демо-набором фронтенда (js/data.js), поэтому интерфейс
 * сразу выглядит так же, как на моках.</p>
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** Логин/пароль/роль — как в демо-доступе фронтенда. */
    private record SeedUser(String login, String password, Role role, String fullName) {
    }

    private record SeedDoctor(String fio, String specialty, String time, int[] jsDays) {
    }

    private final SpecialtyRepository specialtyRepository;
    private final DoctorRepository doctorRepository;
    private final ScheduleSlotRepository scheduleSlotRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public DataSeeder(SpecialtyRepository specialtyRepository,
                      DoctorRepository doctorRepository,
                      ScheduleSlotRepository scheduleSlotRepository,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.seed.enabled:true}") boolean enabled) {
        this.specialtyRepository = specialtyRepository;
        this.doctorRepository = doctorRepository;
        this.scheduleSlotRepository = scheduleSlotRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (userRepository.count() > 0 || doctorRepository.count() > 0) {
            log.debug("БД уже наполнена — сид пропущен");
            return;
        }
        seedUsers();
        Map<String, Specialty> specialties = seedSpecialties();
        seedDoctors(specialties);
        log.info("Демо-данные загружены: {} врачей, {} специальностей",
                doctorRepository.count(), specialtyRepository.count());
    }

    private void seedUsers() {
        List<SeedUser> users = List.of(
                new SeedUser("admin", "admin123", Role.ADMIN, "Администратор системы"),
                new SeedUser("manager", "manager456", Role.ADMIN, "Менеджер клиники"),
                new SeedUser("doctor1", "doctor123", Role.PATIENT, "Врач клиники"),
                new SeedUser("patient", "patient123", Role.PATIENT, "Демо-пациент"));
        for (SeedUser u : users) {
            userRepository.save(new User(u.login(), passwordEncoder.encode(u.password()), u.role(), u.fullName()));
        }
    }

    private Map<String, Specialty> seedSpecialties() {
        Map<String, List<String>> data = new LinkedHashMap<>();
        data.put("Кардиолог", List.of("Гипертоническая болезнь", "Ишемическая болезнь сердца",
                "Аритмия", "Сердечная недостаточность"));
        data.put("Невролог", List.of("Мигрень", "Остеохондроз", "Эпилепсия",
                "Невралгия тройничного нерва"));
        data.put("Хирург", List.of("Аппендицит", "Паховая грыжа", "Холецистит",
                "Варикозное расширение вен"));
        data.put("Терапевт", List.of("ОРВИ", "Гастрит", "Железодефицитная анемия",
                "Хронический бронхит"));
        data.put("Офтальмолог", List.of("Катаракта", "Глаукома", "Конъюнктивит", "Близорукость"));
        data.put("ЛОР", List.of("Синусит", "Тонзиллит", "Отит", "Хронический ринит"));
        data.put("Дерматолог", List.of("Экзема", "Псориаз", "Атопический дерматит", "Кандидоз кожи"));
        data.put("Эндокринолог", List.of("Сахарный диабет", "Гипотиреоз", "Диффузный зоб", "Ожирение"));
        data.put("Гинеколог", List.of("Миома матки", "Эндометриоз", "Кольпит", "Дисфункция яичников"));
        data.put("Уролог", List.of("Мочекаменная болезнь", "Простатит", "Цистит", "Пиелонефрит"));
        data.put("Педиатр", List.of("Рахит", "Диатез", "Кишечная инфекция", "Расстройства сна у детей"));
        data.put("Ортопед", List.of("Переломы", "Артроз", "Сколиоз", "Плоскостопие"));

        Map<String, Specialty> result = new LinkedHashMap<>();
        data.forEach((name, diseases) -> {
            Specialty specialty = specialtyRepository.save(new Specialty(name));
            specialty.setDiseases(diseases);
            result.put(name, specialty);
        });
        return result;
    }

    private void seedDoctors(Map<String, Specialty> specialties) {
        List<SeedDoctor> doctors = List.of(
                new SeedDoctor("Иванов Пётр Сергеевич", "Кардиолог", "09:00", new int[]{1, 3, 5}),
                new SeedDoctor("Смирнова Анна Владимировна", "Невролог", "09:30", new int[]{2, 4}),
                new SeedDoctor("Кузнецов Дмитрий Алексеевич", "Хирург", "10:00", new int[]{1, 4}),
                new SeedDoctor("Попова Елена Игоревна", "Терапевт", "10:30", new int[]{1, 2, 3, 4, 5}),
                new SeedDoctor("Соколов Максим Андреевич", "Офтальмолог", "11:00", new int[]{2, 5}),
                new SeedDoctor("Лебедева Ольга Петровна", "ЛОР", "11:30", new int[]{3, 6}),
                new SeedDoctor("Новиков Сергей Викторович", "Дерматолог", "12:00", new int[]{1, 3}),
                new SeedDoctor("Морозова Татьяна Юрьевна", "Эндокринолог", "12:30", new int[]{2, 4}),
                new SeedDoctor("Волкова Ирина Дмитриевна", "Гинеколог", "13:00", new int[]{1, 2, 4}),
                new SeedDoctor("Егоров Алексей Николаевич", "Уролог", "13:30", new int[]{3, 5}),
                new SeedDoctor("Никитина Светлана Олеговна", "Педиатр", "14:00", new int[]{1, 3, 5}),
                new SeedDoctor("Фёдоров Роман Игоревич", "Ортопед", "14:30", new int[]{2, 6}));

        for (SeedDoctor sd : doctors) {
            Specialty specialty = specialties.get(sd.specialty());
            if (specialty == null) {
                throw new IllegalStateException("Не найдена специальность: " + sd.specialty());
            }
            Doctor doctor = doctorRepository.save(new Doctor(sd.fio(), specialty));
            LocalTime start = LocalTime.parse(sd.time());
            for (int jsDay : sd.jsDays()) {
                // фронтенд считает дни в JS (0=Вс…6=Сб), в БД — ISO (1=Пн…7=Вс)
                int isoDay = jsDay == 0 ? 7 : jsDay;
                scheduleSlotRepository.save(new ScheduleSlot(doctor, isoDay, start));
            }
        }
    }
}
