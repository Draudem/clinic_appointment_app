#!/usr/bin/env python3
"""
Тестовый клиент AudioSocket: играет WAV-файл в порт бэкенда и печатает распознанный текст.

Нужен, чтобы проверить связь БЕЗ Asterisk — «звонок делает» сам скрипт.
Напарник с настоящим Asterisk использует ровно тот же протокол, только dialplan вместо этого шага.

ПРОТОКОЛ (см. Asterisk AudioSocket):
  кадр = type:uint8 + length:uint16(BIG endian) + payload
    0x00 Terminate | 0x01 UUID (16 байт) | 0x10 Audio (PCM int16 8 кГц LE)
Аудио из WAV пересчитывается в 8 кГц — ровно то, что отправляет Asterisk.

ЗАПУСК:
  python3 deploy/audiosocket-probe.py /path/to/recording.wav
  python3 deploy/audiosocket-probe.py --port 8091 --keep 2.0 /path/recording.wav
"""

import argparse
import json
import socket
import struct
import sys
import time
import urllib.request
import wave

TYPE_TERMINATE = 0x00
TYPE_UUID = 0x01
TYPE_AUDIO = 0x10

# 20 мс аудио на 8 кГц моно = 160 сэмплов = 320 байт
CHUNK_SAMPLES = 160


def read_wav_16k_mono(path):
    """Читает WAV и приводит к 16 кГц моно float32 [-1..1]."""
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        channels = w.getnchannels()
        width = w.getsampwidth()
        frames = w.readframes(w.getnframes())

    if width != 2:
        raise SystemExit(f"Поддерживается только 16-битный WAV, получено {width * 8} бит")

    samples = struct.unpack("<%dh" % (len(frames) // 2), frames)

    # Стерео -> моно
    if channels == 2:
        samples = [(samples[i] + samples[i + 1]) // 2 for i in range(0, len(samples), 2)]

    # 16 кГц -> 8 кГц прореживанием (простейшая децимация, для проверки связи достаточно)
    if rate == 16000:
        at8k = list(samples[::2])
    elif rate == 8000:
        at8k = list(samples)
    else:
        step = rate / 8000.0
        at8k = [samples[int(i * step)] for i in range(int(len(samples) / step))]

    return at8k


def to_float(samples):
    return [s / 32768.0 for s in samples]


def send_frame(sock, msg_type, payload=b""):
    # Длина — uint16 в СЕТЕВОМ порядке (big endian)
    sock.sendall(struct.pack("!B", msg_type) + struct.pack("!H", len(payload)) + payload)


def fetch_token(base, login, password):
    """/api/stt/* закрыт авторизацией, поэтому сначала получаем JWT."""
    url = base.rsplit("/api/", 1)[0] + "/api/auth/login"
    req = urllib.request.Request(
        url,
        data=json.dumps({"login": login, "password": password}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r).get("token", "")


def main():
    ap = argparse.ArgumentParser(description="Прогон WAV через AudioSocket и чтение результата")
    ap.add_argument("wav", help="путь к WAV-файлу (желательно 16 кГц моно)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--status-url", default="http://127.0.0.1:8080/api/stt/last",
                    help="откуда забирать распознанный текст")
    ap.add_argument("--token", default="", help="JWT для /api/stt/* (эндпоинты под авторизацией)")
    ap.add_argument("--login", default="admin", help="логин для получения токена")
    ap.add_argument("--password", default="admin123", help="пароль для получения токена")
    ap.add_argument("--keep", type=float, default=0.0,
                    help="сколько секунд дописать тишины в конце, чтобы сработала отсечка")
    ap.add_argument("--realtime", action="store_true",
                    help="отправлять в темпе реального времени (иначе — мгновенно)")
    args = ap.parse_args()

    samples = read_wav_16k_mono(args.wav)
    if args.keep > 0:
        samples += [0] * int(8000 * args.keep)
    print(f"WAV: {len(samples)} сэмплов @8 кГц = {len(samples) / 8000:.2f} с", file=sys.stderr)

    with socket.create_connection((args.host, args.port), timeout=30) as sock:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        send_frame(sock, TYPE_UUID, bytes(range(16)))
        print(f"подключились к {args.host}:{args.port}, шлём UUID", file=sys.stderr)

        for i in range(0, len(samples), CHUNK_SAMPLES):
            block = samples[i:i + CHUNK_SAMPLES]
            if len(block) < CHUNK_SAMPLES:
                block += [0] * (CHUNK_SAMPLES - len(block))
            # int16 little-endian — как требует Asterisk
            pcm = struct.pack("<%dh" % len(block), *[max(-32768, min(32767, int(s))) for s in block])
            send_frame(sock, TYPE_AUDIO, pcm)
            if args.realtime:
                time.sleep(CHUNK_SAMPLES / 8000.0)

        send_frame(sock, TYPE_TERMINATE)
        print("аудио отправлено, шлём Terminate", file=sys.stderr)

    print("ждём распознавания...", file=sys.stderr)
    token = args.token
    if not token:
        try:
            token = fetch_token(args.status_url, args.login, args.password)
            print("токен получен", file=sys.stderr)
        except Exception as e:
            print(f"не удалось получить токен ({e}), передайте --token вручную", file=sys.stderr)

    headers = {"Authorization": "Bearer " + token} if token else {}
    deadline = time.time() + 25
    while time.time() < deadline:
        try:
            req = urllib.request.Request(args.status_url, headers=headers)
            with urllib.request.urlopen(req, timeout=5) as r:
                data = json.load(r)
            if data.get("found") and data.get("text"):
                print("\n>>> РАСПОЗНАНО: «%s»" % data["text"])
                return 0
        except Exception:
            pass
        time.sleep(0.5)

    print("\n>>> Текст не получен за 20 с", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())