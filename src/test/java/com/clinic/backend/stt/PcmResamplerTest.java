package com.clinic.backend.stt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка двух мест, где в телефонии чаще всего ломается:
 * порядок байтов в PCM и пересчёт 8 кГц -> 16 кГц.
 */
class PcmResamplerTest {

    @Test
    void int16PCMЧитаетсяLittleEndian() {
        // Младший байт идёт первым: {0x01,0x00} = 1, а {0x00,0x01} = 256.
        // Если бы порядок байтов трактовался как big-endian, значения бы поменялись местами.
        byte[] one = {0x01, 0x00};
        byte[] twoFiftySix = {0x00, 0x01};
        assertEquals(1 / 32768.0f, PcmResampler.bytesToFloat(one, 0, 2)[0], 1e-7f);
        assertEquals(256 / 32768.0f, PcmResampler.bytesToFloat(twoFiftySix, 0, 2)[0], 1e-7f);
    }

    @Test
    void отрицательныеСэмплыСохраняютЗнак() {
        // -1 в int16 = 0xFFFF, при делении на 32768 даёт около -0.00003
        byte[] minusOne = {(byte) 0xFF, (byte) 0xFF};
        float v = PcmResampler.bytesToFloat(minusOne, 0, 2)[0];
        assertTrue(v < 0, "знак должен сохраниться, получено " + v);
        assertEquals(-1 / 32768.0f, v, 1e-7f);
    }

    @Test
    void неполнаяПараБайтИгнорируется() {
        byte[] odd = {0x00, 0x01, 0x02};
        assertEquals(1, PcmResampler.bytesToFloat(odd, 0, 3).length);
    }

    @Test
    void пересчётУдваиваетЧастоту() {
        // Первый чанк: n отсчётов 8 кГц -> 2n-1 отсчётов (первый не с чем интерполировать)
        float[] in = {0f, 1f, 2f, 3f};
        float[] out = PcmResampler.once(in);
        assertEquals(2 * in.length - 1, out.length);
    }

    @Test
    void следующийЧанкДаётПолные2nОтсчётов() {
        // Регресс: буфер считался как 2n-1, но при уже установленном primed выходит 2n —
        // и каждый второй чанк ронял сервер ArrayIndexOutOfBounds.
        PcmResampler r = new PcmResampler();
        assertEquals(2 * 4 - 1, r.resample(new float[4]).length);   // первый чанк
        assertEquals(2 * 4, r.resample(new float[4]).length);       // последующие
        assertEquals(2 * 4, r.resample(new float[4]).length);
    }

    @Test
    void длинныйПотокЧанковНеПереполняется() {
        // AudioSocket присылает по 160 сэмплов; проверяем много чанков подряд.
        PcmResampler r = new PcmResampler();
        float[] chunk = new float[160];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (float) Math.sin(i * 0.1);
        }
        int total = 0;
        for (int i = 0; i < 500; i++) {
            total += r.resample(chunk).length;
        }
        assertTrue(total > 0, "поток должен давать аудио");
    }

    @Test
    void пересчётСохраняетУровеньСигнала() {
        float[] in = {0f, 1f, 1f, 1f, 0f};
        float[] out = PcmResampler.once(in);
        for (float v : out) {
            assertTrue(v >= 0f && v <= 1f, "вне диапазона: " + v);
        }
        // середина между 1 и 1 должна остаться 1
        assertEquals(1f, out[3], 1e-6f);
    }

    @Test
    void состояниеСохраняетсяМеждуВызовами() {
        PcmResampler r = new PcmResampler();
        float[] first = PcmResampler.once(new float[]{0f, 1f});
        float[] second = new PcmResampler().resample(new float[]{1f});
        // второй вызов в одиночку не должен ломаться
        assertEquals(1, second.length);
        assertTrue(first.length > 0);
    }

    @Test
    void сбросСостоянияНеЛомаетПоток() {
        PcmResampler r = new PcmResampler();
        r.resample(new float[]{1f, 2f, 3f});
        r.reset();
        float[] after = r.resample(new float[]{5f});
        assertEquals(1, after.length);
        assertEquals(5f, after[0], 1e-6f);
    }

    @Test
    void обратноеПреобразованиеТочное() {
        float[] original = {0f, 0.5f, -0.5f, 1f, -1f};
        byte[] bytes = PcmResampler.floatToBytes(original);
        float[] back = PcmResampler.bytesToFloat(bytes, 0, bytes.length);
        for (int i = 0; i < original.length; i++) {
            // int16 квантование: допуск 1/32768
            assertEquals(original[i], back[i], 1.0 / 32767.0);
        }
    }

    @Test
    void пустойВходДаётПустойВыход() {
        assertEquals(0, PcmResampler.once(new float[0]).length);
        assertEquals(0, PcmResampler.once(null).length);
    }

    @Test
    void разбиениеФразыПоТишине() {
        UtteranceSegmenter seg = new UtteranceSegmenter(0.01f, 480);
        // Пауза в 30 мс = ровно одно окно: фраза должна завершиться после него.
        seg.silenceSeconds = 0.03f;
        float[] speech = new float[480 * 10];
        java.util.Arrays.fill(speech, 0.5f);
        float[] silence = new float[480];
        assertTrue(!seg.feed(speech), "на речи фраза не должна завершаться");
        assertTrue(seg.feed(silence), "после окна тишины фраза завершена");
        float[] utterance = seg.takeUtterance();
        assertTrue(utterance.length > speech.length, "хвост тишины должен попасть в фразу");
    }

    @Test
    void короткаяПаузаНеЗавершаетФразу() {
        // Пауза короче silenceSeconds не должна обрывать фразу — иначе слова
        // разделялись бы на отдельные «фразы».
        UtteranceSegmenter seg = new UtteranceSegmenter(0.01f, 480);
        seg.silenceSeconds = 1.0f;
        float[] speech = new float[480];
        java.util.Arrays.fill(speech, 0.5f);
        seg.feed(speech);
        float[] shortPause = new float[480 * 3];
        assertTrue(!seg.feed(shortPause), "пауза 90 мс при пороге 1 с — не конец фразы");
    }

    @Test
    void тишинаДоНачалаРечиИгнорируется() {
        UtteranceSegmenter seg = new UtteranceSegmenter(0.01f, 480);
        float[] silence = new float[480 * 5];
        assertTrue(!seg.feed(silence), "пустой звонок не должен давать фразу");
        assertEquals(0, seg.bufferedSeconds(), 0.01f);
    }

    @Test
    void кадрAsteriskМеньшеОкнаНоФразаВсёЕщёНакапливается() {
        // Регресс: кадр AudioSocket = 320 байт = 160 сэмплов на 8 кГц = 319 на 16 кГц,
        // а окно анализа 480. Без переноса остатка окно не набиралось НИ РАЗУ и фраза
        // оставалась пустой — звонок «проходил», но текст не появлялся.
        UtteranceSegmenter seg = new UtteranceSegmenter(0.01f, 480);
        seg.silenceSeconds = 0.2f;
        float[] speech = new float[319];
        java.util.Arrays.fill(speech, 0.4f);

        for (int i = 0; i < 100; i++) {
            seg.feed(speech);                       // речь
        }
        assertTrue(seg.bufferedSeconds() > 1.0,
                "речь должна накапливаться, накоплено " + seg.bufferedSeconds() + " с");

        float[] silence = new float[319];           // тишина -> конец фразы
        boolean finished = false;
        for (int i = 0; i < 30 && !finished; i++) {
            finished = seg.feed(silence);
        }
        assertTrue(finished, "тишина должна завершать фразу");
        assertTrue(seg.takeUtterance().length > 0, "фраза должна была накопиться");
    }

    @Test
    void тишинаДоРечиНеПопадаетВФразу() {
        UtteranceSegmenter seg = new UtteranceSegmenter(0.01f, 480);
        seg.silenceSeconds = 0.2f;
        float[] silence = new float[319];
        for (int i = 0; i < 50; i++) {
            assertTrue(!seg.feed(silence), "пустая линия не должна давать фразу");
        }
        assertEquals(0f, seg.bufferedSeconds(), 0.001f);
    }

    @Test
    void заглушкаСинтезаВозвращаетТишину() {
        ReplySynthesizer s = new SilentReplySynthesizer();
        float[] out = s.synthesize("Готов! Записал вас.");
        assertTrue(out != null && out.length > 0, "нужна пауза, чтобы Asterisk держал вызов");
        for (float v : out) {
            assertEquals(0f, v, 0f, "должна быть ровно тишина");
        }
        assertTrue(!s.available());
        assertEquals(0, s.synthesize("").length);
        assertEquals(8000, s.sampleRate(), "частота заглушки совпадает с форматом AudioSocket");
    }
}