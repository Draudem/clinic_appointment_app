package com.clinic.backend.nlu;

import java.util.List;

/** Полный разбор фрейма для UI/мобилки: и сырая строка, и разобранные поля. */
public record NluParseResponse(
        String frame,
        String intent,
        String specialty,
        List<String> timeFactors,
        boolean timeUnknown,
        List<String> symptoms,
        boolean needsClarification,
        boolean unsupported,
        String sourceText,
        String model) {

    public static NluParseResponse from(String sourceText, NluFrame f, String model) {
        return new NluParseResponse(
                f.frame(),
                f.intent(),
                f.specialty(),
                f.timeFactors(),
                f.timeUnknown(),
                f.symptoms(),
                f.needsClarification(),
                f.unsupported(),
                sourceText,
                model);
    }
}
