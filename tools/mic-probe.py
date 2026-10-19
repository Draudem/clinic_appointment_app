#!/usr/bin/env python3
"""
Говорит в микрофон — сервер распознаёт. Проверка связи без Asterisk.

Скрипт делает вид абонента: снимает микрофон в формате, который отдаёт
Asterisk (PCM 16 бит, 8 кГц, моно), и шлёт его в порт AudioSocket.
Параллельно опрашивает открытый эндпоинт /api/stt/public/last и печатает
распознанное.

ЗАПУСК (бэкенд уже запущен):
  python3 deploy/mic-probe.py
  python3 deploy/mic-probe.py --host 10.0.2.15 --device default
  Ctrl+C — закончить звонок (сервер получит Terminate)

Нужны: arecord (alsa-utils) и python3. Ничего больше ставить не надо.
"""

import argparse
import json
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request

TYPE_TERMINATE = 0x00
TYPE_UUID = 0x01
TYPE_AUDIO = 0x10
CHUNK_BYTES = 320          # 20 мс на 8 кГц — ровно как шлёт Asterisk
SAMPLE_RATE = 8000


def frame(msg_type, payload=b""):
    """Кадр AudioSocket: type uint8 + length uint16 BIG endian + payload."""
    return struct.pack("!B", msg_type) + struct.pack("!H", len(payload)) + payload


def poll_text(url, stop_event, seen):
    """Фоново опрашивает сервер и печатает новые распознанные фразы."""
    while not stop_event.is_set():
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                data = json.load(r)
            if data.get("found") and data.get("text") and data["text"] not in seen:
                seen.add(data["text"])
                stamp = data.get("at", "")[-8:]
                print("\n\033[32m>>> %s  «%s»\033[0m" % (stamp, data["text"]), flush=True)
        except Exception:
            pass
        stop_event.wait(0.7)


def main():
    ap = argparse.ArgumentParser(description="Живая проверка STT через микрофон")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--device", default="default", help="устройство записи для arecord")
    ap.add_argument("--rate", type=int, default=SAMPLE_RATE,
                    help="частота записи; сервер ожидает 8000")
    args = ap.parse_args()

    status_url = "http://%s:8080/api/stt/public/status" % args.host

    # Проверяем, что сервер вообще готов принимать звонок
    try:
        with urllib.request.urlopen(status_url, timeout=5) as r:
            st = json.load(r)
        if not st.get("listening"):
            print("сервер НЕ слушает порт %d — проверь /api/stt/status" % args.port, file=sys.stderr)
            return 1
        print("сервер готов, модель загружена", file=sys.stderr)
    except Exception as e:
        print("не удалось достучаться до %s: %s" % (status_url, e), file=sys.stderr)
        print("бэкенд запущен? LD_LIBRARY_PATH=./lib:$LD_LIBRARY_PATH ./gradlew bootRun", file=sys.stderr)
        return 1

    arecord = ["arecord", "-q", "-D", args.device,
               "-f", "S16_LE", "-r", str(args.rate), "-c", "1", "-t", "raw", "-"]

    proc = subprocess.Popen(arecord, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    sock = socket.create_connection((args.host, args.port), timeout=15)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.sendall(frame(TYPE_UUID, bytes(range(16))))

    stop_event = threading.Event()
    seen = set()
    watcher = threading.Thread(
        target=poll_text,
        args=("http://%s:8080/api/stt/public/last" % args.host, stop_event, seen),
        daemon=True)
    watcher.start()

    print("говорите.  Ctrl+C — закончить звонок.\n", file=sys.stderr)

    def finish(*_):
        stop_event.set()
    signal.signal(signal.SIGINT, finish)

    total = 0
    try:
        while not stop_event.is_set():
            data = proc.stdout.read(CHUNK_BYTES)
            if not data or len(data) < CHUNK_BYTES:
                break
            sock.sendall(frame(TYPE_AUDIO, data))
            total += len(data)
            if total % (SAMPLE_RATE * 2 * 10) == 0:      # раз в 10 секунд
                print("  ... передано %.0f с" % (total / (SAMPLE_RATE * 2.0)),
                      file=sys.stderr, end="\r")
    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        print("\nзавершаем звонок (Terminate)...", file=sys.stderr)
        try:
            sock.sendall(frame(TYPE_TERMINATE))
        except OSError:
            pass
        time.sleep(1.5)
        sock.close()
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()

        # Даём серверу дописать последнюю фразу и показать её
        stop_event.clear()
        time.sleep(1.0)
        try:
            with urllib.request.urlopen(
                    "http://%s:8080/api/stt/public/last" % args.host, timeout=5) as r:
                data = json.load(r)
            if data.get("found") and data.get("text"):
                print("\nитог: «%s»" % data["text"])
        except Exception:
            pass
        print("\nвсего передано %.1f с аудио" % (total / (SAMPLE_RATE * 2.0)))


if __name__ == "__main__":
    main()