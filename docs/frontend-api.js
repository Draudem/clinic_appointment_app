/**
 * ЗАМЕНА js/api.js — реальные запросы к бэкенду (Spring Boot).
 *
 * Использование: положить файл вместо js/api.js в index.html
 * (тег <script src="js/api.js"> остаётся тем же).
 *
 * Форматы ответов совпадают с тем, что ожидает app.js:
 *   API.login(login, password)      → {login, role: "администратор" | "пациент"}
 *   API.getDoctors()                → [{id, fio, specialty, time, days, nextSlot}]
 *   API.getScheduleByDate(date)     → [{id, fio, specialty, time, days, date, available}]
 *   API.getSpecialties()            → [{name, diseases}]
 *   API.getUsers()                  → [{login, password, role}]  (только админ)
 *   API.checkTimesUnique()          → {ok: true} | {ok: false, time: "09:00"}
 *
 * Дополнительно (для новых разделов):
 *   API.getEmployees()              → [{fio, specialty, nextSlot, ...}]
 *   API.getCalendar(year, month)    → [{date, count}]
 *   API.register(login, password, fullName)
 *   API.logout()
 */
window.API = (function () {
  // адрес бэкенда (поменять при деплое)
  const BASE_URL = "http://localhost:8080/api";
  const TOKEN_KEY = "clinic_jwt";

  const token = () => localStorage.getItem(TOKEN_KEY);

  const pad = (n) => String(n).padStart(2, "0");
  const toIsoDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

  async function request(path, options = {}) {
    const headers = Object.assign({ Accept: "application/json" }, options.headers || {});
    const jwt = token();
    if (jwt) headers.Authorization = "Bearer " + jwt;
    if (typeof options.body === "string") headers["Content-Type"] = "application/json";

    const response = await fetch(BASE_URL + path, Object.assign({}, options, { headers }));

    if (response.status === 204) return null;

    let data = null;
    try {
      data = await response.json();
    } catch (_) {
      /* пустое тело */
    }

    if (!response.ok) {
      if (response.status === 401) localStorage.removeItem(TOKEN_KEY);
      throw new Error((data && data.message) || "Ошибка сервера (" + response.status + ")");
    }
    return data;
  }

  return {
    async login(login, password) {
      const data = await request("/auth/login", {
        method: "POST",
        body: JSON.stringify({ login, password })
      });
      localStorage.setItem(TOKEN_KEY, data.token);
      return { login: data.login, role: data.role, roleCode: data.roleCode };
    },

    async register(login, password, fullName) {
      const data = await request("/auth/register", {
        method: "POST",
        body: JSON.stringify({ login, password, fullName })
      });
      localStorage.setItem(TOKEN_KEY, data.token);
      return { login: data.login, role: data.role };
    },

    logout() {
      localStorage.removeItem(TOKEN_KEY);
    },

    async me() {
      return request("/auth/me");
    },

    async getDoctors() {
      return request("/doctors");
    },

    async getScheduleByDate(date) {
      return request("/schedule?date=" + toIsoDate(date));
    },

    async getSpecialties() {
      return request("/specialties");
    },

    async getUsers() {
      return request("/users");
    },

    async checkTimesUnique() {
      return request("/schedule/unique-times");
    },

    async getEmployees() {
      return request("/employees");
    },

    async getCalendar(year, month) {
      return request(`/calendar?year=${year}&month=${month}`);
    }
  };
})();
