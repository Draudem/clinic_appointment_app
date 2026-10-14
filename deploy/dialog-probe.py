#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка двустороннего голосового диалога с сервером БЕЗ Asterisk.

Скрипт играет роль телефонии: подключается к порту AudioSocket, слушает голос
ассистента и одновременно передаёт серверу то, что говорит микрофон.
Распознанный текст печатается в терминале.

    python3 deploy/dialog-probe.py --host 10.2.171.217

Что должно произойти:
  1) вы подключаетесь, сервер отвечает голосом «Для записи на приём сообщите...»;
  2) вы говорите в микрофон — сервер пишет вашу речь в лог и по HTTP;
  3) после паузы ~1.2 с сервер отвечает «Спасибо, записываю» и закрывает связь.

ЧТО НУЖНО
----------
python3 — и всё. Микрофон берётся через arecord (пакет alsa-utils), воспроизведение
через paplay / ffplay / aplay — в зависимости от того, как устроен звук.

  • если звуком управляет PulseAudio или PipeWire (обычная Ubuntu/Fedora) — нужен
    paplay ( PulseAudio ) или ffplay; прямой aplay там падает с
    "Device or resource busy", потому что устройство уже занято звуковым сервером;
  • если звук идёт напрямую через ALSA — подойдёт aplay.

Скрипт пробует варианты по очереди и сам переключается на следующий, если
воспроизведение не удалось. Голос сервера в любом случае пишется в WAV-файл,
поэтому проверка не срывается из-за звука.

Ничего ставить не нужно, если arecord уже есть. Голоса не будет — работает
--no-play, результат всё равно в WAV и в распознанном тексте.

ПОЧЕМУ ЭТО ВОЗМОЖНО БЕЗ ASTERISK
-----------------------------------
AudioSocket — обычный двусторонний TCP: клиент шлёт аудио и одновременно читает
аудио от сервера. Роль Asterisk здесь может занимать любая программа.
"""
import argparse
import json
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
import wave

# Без этого stdout при перенаправлении в файл или конвейер копится буфером и
# сообщения выходят не в том порядке, в котором произошли, — при отладке
# телефонии это сбивает с толку.
try:
    sys.stdout.reconfigure(line_buffering=True)
except AttributeError:
    pass

TYPE_TERMINATE = 0x00
TYPE_UUID = 0x01
TYPE_AUDIO = 0x10
AUDIO_RATE = 8000
FRAME = 320                      # 20 мс — как у Asterisk

# Кандидаты для воспроизведения, в порядке предпочтения.
# Первый идёт paplay: на системах с PulseAudio (а это почти все ноутбуки) прямой
# aplay к ALSA-устройству не может его открыть — устройство занято звуковым сервером.
PLAYERS = [
    ("paplay", ["paplay"]),
    ("ffplay", ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet",
                "-f", "s16le", "-ar", str(AUDIO_RATE), "-ac", "1", "-i", "-"]),
    ("aplay (pulse)", ["aplay", "-q", "-D", "pulse",
                       "-r", str(AUDIO_RATE), "-f", "S16_LE", "-c", "1", "-"]),
    ("aplay (direct)", ["aplay", "-q",
                        "-r", str(AUDIO_RATE), "-f", "S16_LE", "-c", "1", "-"]),
]


def frame(msg_type, payload=b""):
    """Кадр AudioSocket: type uint8 + length uint16 (BIG endian) + payload."""
    return struct.pack("!B", msg_type) + struct.pack("!H", len(payload)) + payload


def recv_exact(sock, n):
    """Читает ровно n байт ИЗ СОКЕТА. None — соединение закрыто.

    Отдельная функция от read_exact: у сокета свой метод recv(), у канала
    процесса — read(). Раньше здесь была одна функция на оба случая, и при
    чтении микрофона скрипт падал с AttributeError.
    """
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def read_exact(fh, n):
    """Читает ровно n байт ИЗ ФАЙЛА/КАНАЛА ПРОЦЕССА (stdout arecord)."""
    buf = b""
    while len(buf) < n:
        chunk = fh.read(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


class VoiceOut:
    """Аудио от сервера: всегда пишется в WAV, при удаче ещё и проигрывается.

    Плеер выбирается по списку кандидатов. Если он умер (устройство занято,
    нет такого устройства) — молча переходим к следующему и пишем в stderr,
    по которому видно, что произошло.
    """

    def __init__(self, wav_path, play, forced_player=""):
        self.wav_path = wav_path
        self.play = play
        self.forced = forced_player
        self.player = None
        self.player_name = ""
        self.candidate = 0
        self.wav = None
        self.total = 0
        self.lock = threading.Lock()
        if play:
            self._start_next()

    def _available(self):
        """Список кандидатов, которые есть в системе и подходят под --player."""
        if self.forced:
            for name, cmd in PLAYERS:
                if name == self.forced or cmd[0] == self.forced:
                    return [(name, cmd)] if shutil.which(cmd[0]) else []
            return []
        out = [(n, c) for n, c in PLAYERS if shutil.which(c[0])]
        # На системе с PulseAudio aplay почти наверняка не заработает, поэтому
        # не тратим на него попытку, если рядом есть paplay.
        if any(n == "paplay" for n, _ in out):
            out = [(n, c) for n, c in out if n != "aplay (direct)"]
        return out

    def _start_next(self):
        """Поднимает очередной плеер. False — кандидаты кончились."""
        available = self._available()
        if not available:
            if self.player is None:
                print("  \033[33mЗвуковых устройств для воспроизведения нет — "
                      "голос пишем только в WAV\033[0m", file=sys.stderr)
            self.play = False
            return False
        if self.candidate >= len(available):
            self.play = False
            return False
        name, cmd = available[self.candidate]
        self.candidate += 1
        try:
            # stderr в /dev/null: сообщения вроде "Device or resource busy" от
            # ALSA сыпятся при каждом открытии и только путают вывод.
            self.player = subprocess.Popen(cmd, stdin=subprocess.PIPE,
                                           stderr=subprocess.DEVNULL)
            self.player_name = name
            return True
        except OSError as e:
            print("  \033[33m%s не запустился: %s\033[0m" % (name, e), file=sys.stderr)
            self.player = None
            return self._start_next()

    def write(self, pcm):
        with self.lock:
            if self.wav is None:
                self.wav = wave.open(self.wav_path, "wb")
                self.wav.setnchannels(1)
                self.wav.setsampwidth(2)
                self.wav.setframerate(AUDIO_RATE)
            self.wav.writeframes(pcm)
            self.total += len(pcm)
            if not self.play or self.player is None:
                return
            # Плеер мог умереть сразу после старта (устройство занято) —
            # это видно только здесь, при первой записи в его stdin.
            if self.player.poll() is not None:
                self._died()
                return
            try:
                self.player.stdin.write(pcm)
            except (BrokenPipeError, OSError, ValueError):
                self._died()

    def _died(self):
        """Плеер не работает: сообщаем и пробуем следующего кандидата."""
        print("  \033[33m%s не смог воспроизвести (устройство занято или "
              "отсутствует) — пробуем другой\033[0m" % self.player_name,
              file=sys.stderr)
        try:
            self.player.kill()
        except Exception:
            pass
        self.player = None
        self._start_next()

    def finish(self):
        with self.lock:
            if self.wav:
                self.wav.close()
                self.wav = None
            if self.player:
                try:
                    self.player.stdin.close()
                    self.player.wait(timeout=10)
                except Exception:
                    try:
                        self.player.kill()
                    except Exception:
                        pass
                self.player = None
            return self.total


def snapshot_existing(url):
    """Что уже было в хранилище ДО нашего звонка.

    TranscriptStore живёт в памяти и хранит последние фразы, в том числе из
    чужих прошлых прогонов. Без этого снимка скрипт напечатал бы чужой текст
    из вчерашнего теста как «вы только что сказали». Сверяемся по метке времени
    от сервера, а не по локальным часам — они могут расходиться.
    """
    try:
        with urllib.request.urlopen(url, timeout=4) as r:
            d = json.load(r)
        return {u.get("at") for u in d.get("utterances", []) if u.get("at")}
    except Exception:
        return set()


def poller(url, stop, seen, texts, baseline):
    """Печатает новые распознанные фразы — только те, что появились при этом звонке."""
    while not stop.is_set():
        try:
            with urllib.request.urlopen(url, timeout=4) as r:
                d = json.load(r)
            at = d.get("at")
            text = d.get("text")
            if d.get("found") and text and at not in baseline and at not in seen:
                seen.add(at)
                texts.append((at, text))
                hhmm = at[11:16] if len(at) >= 16 else ""
                print("\n\033[36m  ВЫ СКАЗАЛИ (%s): %s\033[0m" % (hhmm, text), flush=True)
        except Exception:
            pass
        stop.wait(0.5)


def start_mic(device):
    """Запускает arecord. Возвращает (процесс, поток чтения его stderr)."""
    cmd = ["arecord", "-D", device, "-f", "S16_LE",
           "-r", str(AUDIO_RATE), "-c", "1", "-t", "raw", "-"]
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=0,
                                stderr=subprocess.PIPE)
    except OSError as e:
        print("Не удалось запустить arecord: %s" % e, file=sys.stderr)
        return None, None, ""

    # stderr не теряем: если микрофон не открылся, arecord напишет почему,
    # а раньше сообщение уходило в /dev/null и скрипт молчал.
    err_lines = []

    def drain():
        for raw in proc.stderr:
            line = raw.decode("utf-8", "replace").strip()
            if line:
                err_lines.append(line)

    thread = threading.Thread(target=drain, daemon=True)
    thread.start()
    return proc, thread, err_lines


def main():
    ap = argparse.ArgumentParser(description="Двусторонний тест голосового диалога без Asterisk")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--audio-port", type=int, default=8091)
    ap.add_argument("--http-port", type=int, default=8080)
    ap.add_argument("--device", default="default", help="микрофон для arecord (см. arecord -l)")
    ap.add_argument("--file", default="", help="вместо микрофона отправить WAV")
    ap.add_argument("--wav", default="reply.wav", help="куда сохранять голос сервера")
    ap.add_argument("--no-play", action="store_true", help="не играть, только сохранить WAV")
    ap.add_argument("--player", default="",
                    help="задать плеер: paplay | ffplay | aplay (pulse) | aplay (direct)")
    ap.add_argument("--list-devices", action="store_true",
                    help="показать доступные микрофоны и выйти")
    ap.add_argument("--max-seconds", type=int, default=180,
                    help="предел длительности звонка, чтобы не висеть при молчании")
    args = ap.parse_args()

    if args.list_devices:
        print("Микрофоны (первый — обычно рабочий):")
        subprocess.run(["arecord", "-l"])
        return 0

    base = "http://%s:%d" % (args.host, args.http_port)
    recent_url = "%s/api/stt/public/recent" % base
    last_url = "%s/api/stt/public/last" % base

    # Снимок того, что было до нас — иначе напечатаем чужой старый текст.
    baseline = snapshot_existing(recent_url)
    if baseline:
        print("В хранилище уже есть %d фраз(а) от прошлых звонков — "
              "показываю только новые." % len(baseline))

    print("Подключаемся к %s:%d (AudioSocket)…" % (args.host, args.audio_port))
    try:
        sock = socket.create_connection((args.host, args.audio_port), timeout=15)
    except Exception as e:
        print("Не удалось подключиться: %s" % e, file=sys.stderr)
        print("Проверьте, что бэкенд запущен и порт %d открыт." % args.audio_port,
              file=sys.stderr)
        return 1
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    # Таймаут из create_connection действует и на чтение, а это ловушка: после
    # приветствия сервер несколько секунд молчит, и если звонящий думает
    # дольше 15 секунд, recv() бросает SocketTimeout и поток чтения умирает —
    # ответ сервера потом уже не будет ни услышан, ни сохранён.
    # Для чтения нам нужно обычное блокирующее поведение.
    sock.settimeout(None)

    stop = threading.Event()
    seen, texts = set(), []
    threading.Thread(target=poller, args=(last_url, stop, seen, texts, baseline),
                     daemon=True).start()

    voice = VoiceOut(args.wav, not args.no_play, args.player)
    if not args.no_play and voice.player_name:
        print("Пробуем воспроизведение через: %s" % voice.player_name)
    done = threading.Event()

    # --- поток чтения: принимаем голос сервера ---
    def reader():
        try:
            while not stop.is_set():
                head = recv_exact(sock, 3)
                if head is None:
                    print("\n  Сервер закрыл соединение — звонок завершён.", flush=True)
                    break
                t = head[0]
                length = (head[1] << 8) | head[2]
                if length > 1_000_000:
                    print("\n  Невозможная длина кадра %d — разрываем." % length, flush=True)
                    break
                payload = recv_exact(sock, length) if length else b""
                if payload is None:
                    break
                if t == TYPE_AUDIO:
                    voice.write(payload)
                elif t == TYPE_TERMINATE:
                    print("\n  Terminate от сервера.", flush=True)
                    break
        except OSError as e:
            # Сворачиваем всё в done.set() ниже: необработанное исключение в
            # потоке печатает страшный трейсбек и молча теряет оставшийся звук.
            print("\n  Ошибка чтения сокета: %s" % e, flush=True)
        finally:
            done.set()

    threading.Thread(target=reader, daemon=True).start()
    sock.sendall(frame(TYPE_UUID, bytes(range(16))))

    # --- отправка: микрофон или файл ---
    # Сервер держит линию открытой, пока идёт звук: его таймаут чтения сбрасывается
    # каждым кадром. Если молчать, звонок не закончится никогда, поэтому у скрипта
    # свой предел времени, иначе «забытый» прогон висит до Ctrl+C.
    deadline = time.monotonic() + args.max_seconds
    state = {"mic_failed": False, "stopped_early": False}
    rec = None

    try:
        if args.file:
            print("Отправляем файл %s (режим без микрофона)…" % args.file, file=sys.stderr)
            with wave.open(args.file, "rb") as w:
                data = w.readframes(w.getnframes())
                rate = w.getframerate()
            s = struct.unpack("<%dh" % (len(data) // 2), data)
            if rate != AUDIO_RATE:
                step = rate / float(AUDIO_RATE)
                s = [s[int(i * step)] for i in range(int(len(s) / step))]
            s = list(s) + [0] * (AUDIO_RATE * 3)  # тишина в конце, чтобы сработала отсечка
            for i in range(0, len(s), FRAME // 2):
                b = s[i:i + FRAME // 2]
                b += [0] * (FRAME // 2 - len(b))
                try:
                    sock.sendall(frame(TYPE_AUDIO, struct.pack("<%dh" % len(b), *b)))
                except OSError:
                    break
        else:
            if not shutil.which("arecord"):
                print("arecord не найден. Установите alsa-utils или используйте --file.",
                      file=sys.stderr)
                return 1
            print("\n\033[32mГоворите в микрофон. Звонок завершится сам, "
                  "Ctrl+C — прервать.\033[0m", flush=True)
            rec, err_thread, err_lines = start_mic(args.device)
            # Условие — на done, а не на stop: если сервер закроет связь
            # (звонок завершён, звонящий повесил трубку), поток чтения выставит
            # done, и этот цикл тоже обязан выйти. Иначе скрипт навсегда остался бы
            # висеть на чтении микрофона.
            while not stop.is_set() and not done.is_set():
                if time.monotonic() > deadline:
                    print("\n  \033[33mДостигнут предел времени (%d с) — завершаем.\033[0m"
                          % args.max_seconds, flush=True)
                    state["stopped_early"] = True
                    break
                # Здесь именно read_exact: поток arecord — это файл, а не сокет.
                data = read_exact(rec.stdout, FRAME)
                if data is None:
                    if rec.poll() is not None and err_lines:
                        print("\n\033[31m  Микрофон не работает: %s\033[0m"
                              % err_lines[0], flush=True)
                        print("  Список микрофонов: python3 %s --list-devices"
                              % __file__, file=sys.stderr)
                        state["mic_failed"] = True
                    break
                try:
                    sock.sendall(frame(TYPE_AUDIO, data))
                except OSError:
                    break

    except KeyboardInterrupt:
        print("\n  Прервано пользователем.", flush=True)
        state["stopped_early"] = True
        stop.set()
        done.set()
    finally:
        # Очистка микрофона обязана переживать повторное прерывание: иначе Ctrl+C
        # попадёт прямо в subprocess.wait() и итог пропадёт вместе с трейсбеком.
        if rec is not None:
            try:
                rec.terminate()
                rec.wait(timeout=3)
            except (Exception, KeyboardInterrupt):
                try:
                    rec.kill()
                except Exception:
                    pass

    # Ждём, пока сервер договорит и закроет соединение. Если вышли сами
    # (предел времени или Ctrl+C), ждать нечего — ждать 30 секунд ради
    # заведомо пустого ожидания неприятно.
    done.wait(timeout=2 if state["stopped_early"] else 30)
    stop.set()
    time.sleep(0.6)
    total = voice.finish()
    try:
        sock.sendall(frame(TYPE_TERMINATE))
    except OSError:
        pass
    sock.close()

    print("\n" + "=" * 56)
    print("Голос сервера: %.1f с -> %s" % (total / 2.0 / AUDIO_RATE, args.wav))
    if texts:
        print("Распознано ваших фраз: %d" % len(texts))
        for at, t in texts:
            print("   • [%s] %s" % (at[11:19] if len(at) >= 19 else at, t))
    elif state["mic_failed"]:
        print("Ничего не распознано: микрофон не открылся (см. сообщение выше).")
    else:
        print("Ваша речь не распознана — проверьте микрофон и уровень записи.")
    print("=" * 56)
    return 0 if texts else 2


if __name__ == "__main__":
    sys.exit(main())