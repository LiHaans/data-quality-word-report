package com.example.dqreport.util;

import com.carrotsearch.labs.langid.DetectedLanguage;
import com.carrotsearch.labs.langid.LangIdV3;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LanguageDetector {

    private static final double TRADITIONAL_THRESHOLD = 0.05;
    private static final int FILIPINO_SCORE_THRESHOLD = 4;
    private static final int FILIPINO_MIN_TEXT_LENGTH = 15;

    private static final Map<String, String> ISO_639_1_TO_3 = new HashMap<String, String>();
    static {
        ISO_639_1_TO_3.put("af", "afr");
        ISO_639_1_TO_3.put("am", "amh");
        ISO_639_1_TO_3.put("an", "arg");
        ISO_639_1_TO_3.put("ar", "ara");
        ISO_639_1_TO_3.put("as", "asm");
        ISO_639_1_TO_3.put("az", "aze");
        ISO_639_1_TO_3.put("be", "bel");
        ISO_639_1_TO_3.put("bg", "bul");
        ISO_639_1_TO_3.put("bn", "ben");
        ISO_639_1_TO_3.put("br", "bre");
        ISO_639_1_TO_3.put("bs", "bos");
        ISO_639_1_TO_3.put("ca", "cat");
        ISO_639_1_TO_3.put("cs", "ces");
        ISO_639_1_TO_3.put("cy", "cym");
        ISO_639_1_TO_3.put("da", "dan");
        ISO_639_1_TO_3.put("de", "deu");
        ISO_639_1_TO_3.put("dz", "dzo");
        ISO_639_1_TO_3.put("el", "ell");
        ISO_639_1_TO_3.put("en", "eng");
        ISO_639_1_TO_3.put("eo", "epo");
        ISO_639_1_TO_3.put("es", "spa");
        ISO_639_1_TO_3.put("et", "est");
        ISO_639_1_TO_3.put("eu", "eus");
        ISO_639_1_TO_3.put("fa", "fas");
        ISO_639_1_TO_3.put("fi", "fin");
        ISO_639_1_TO_3.put("fo", "fao");
        ISO_639_1_TO_3.put("fr", "fra");
        ISO_639_1_TO_3.put("ga", "gle");
        ISO_639_1_TO_3.put("gl", "glg");
        ISO_639_1_TO_3.put("gu", "guj");
        ISO_639_1_TO_3.put("he", "heb");
        ISO_639_1_TO_3.put("hi", "hin");
        ISO_639_1_TO_3.put("hr", "hrv");
        ISO_639_1_TO_3.put("ht", "hat");
        ISO_639_1_TO_3.put("hu", "hun");
        ISO_639_1_TO_3.put("hy", "hye");
        ISO_639_1_TO_3.put("id", "ind");
        ISO_639_1_TO_3.put("is", "isl");
        ISO_639_1_TO_3.put("it", "ita");
        ISO_639_1_TO_3.put("ja", "jpn");
        ISO_639_1_TO_3.put("jv", "jav");
        ISO_639_1_TO_3.put("ka", "kat");
        ISO_639_1_TO_3.put("kk", "kaz");
        ISO_639_1_TO_3.put("km", "khm");
        ISO_639_1_TO_3.put("kn", "kan");
        ISO_639_1_TO_3.put("ko", "kor");
        ISO_639_1_TO_3.put("ku", "kur");
        ISO_639_1_TO_3.put("ky", "kir");
        ISO_639_1_TO_3.put("la", "lat");
        ISO_639_1_TO_3.put("lb", "ltz");
        ISO_639_1_TO_3.put("lo", "lao");
        ISO_639_1_TO_3.put("lt", "lit");
        ISO_639_1_TO_3.put("lv", "lav");
        ISO_639_1_TO_3.put("mg", "mlg");
        ISO_639_1_TO_3.put("mk", "mkd");
        ISO_639_1_TO_3.put("ml", "mal");
        ISO_639_1_TO_3.put("mn", "mon");
        ISO_639_1_TO_3.put("mr", "mar");
        ISO_639_1_TO_3.put("ms", "msa");
        ISO_639_1_TO_3.put("mt", "mlt");
        ISO_639_1_TO_3.put("nb", "nob");
        ISO_639_1_TO_3.put("ne", "nep");
        ISO_639_1_TO_3.put("nl", "nld");
        ISO_639_1_TO_3.put("nn", "nno");
        ISO_639_1_TO_3.put("no", "nor");
        ISO_639_1_TO_3.put("oc", "oci");
        ISO_639_1_TO_3.put("or", "ori");
        ISO_639_1_TO_3.put("pa", "pan");
        ISO_639_1_TO_3.put("pl", "pol");
        ISO_639_1_TO_3.put("ps", "pus");
        ISO_639_1_TO_3.put("pt", "por");
        ISO_639_1_TO_3.put("qu", "que");
        ISO_639_1_TO_3.put("ro", "ron");
        ISO_639_1_TO_3.put("ru", "rus");
        ISO_639_1_TO_3.put("rw", "kin");
        ISO_639_1_TO_3.put("se", "sme");
        ISO_639_1_TO_3.put("si", "sin");
        ISO_639_1_TO_3.put("sk", "slk");
        ISO_639_1_TO_3.put("sl", "slv");
        ISO_639_1_TO_3.put("sq", "sqi");
        ISO_639_1_TO_3.put("sr", "srp");
        ISO_639_1_TO_3.put("sv", "swe");
        ISO_639_1_TO_3.put("sw", "swa");
        ISO_639_1_TO_3.put("ta", "tam");
        ISO_639_1_TO_3.put("te", "tel");
        ISO_639_1_TO_3.put("th", "tha");
        ISO_639_1_TO_3.put("tl", "tgl");
        ISO_639_1_TO_3.put("tr", "tur");
        ISO_639_1_TO_3.put("ug", "uig");
        ISO_639_1_TO_3.put("uk", "ukr");
        ISO_639_1_TO_3.put("ur", "urd");
        ISO_639_1_TO_3.put("uz", "uzb");
        ISO_639_1_TO_3.put("vi", "vie");
        ISO_639_1_TO_3.put("vo", "vol");
        ISO_639_1_TO_3.put("wa", "wln");
        ISO_639_1_TO_3.put("xh", "xho");
        ISO_639_1_TO_3.put("yi", "yid");
        ISO_639_1_TO_3.put("zu", "zul");
    }

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern MENTION_PATTERN = Pattern.compile("@[\\w\\u4e00-\\u9fff]+");
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#[\\w\\u4e00-\\u9fff]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private static final String[] FILIPINO_STRONG_MARKERS = new String[] {
            "mga", "hindi", "salamat", "kamusta", "bakit", "wala", "pamilya"
    };

    private static final String[] FILIPINO_MEDIUM_MARKERS = new String[] {
            "ang", "ng", "para", "sa", "si", "sila", "kayo", "kami", "tayo", "pero", "kung", "gusto", "mahal"
    };

    private final LangIdV3 langId;

    public LanguageDetector() {
        this.langId = new LangIdV3();
    }

    public String detect(String text) {
        return detectDetailed(text).getLang();
    }

    public LanguageDetectionResult detectDetailed(String text) {
        if (StringUtils.isBlank(text)) {
            return new LanguageDetectionResult("-", "-", 0D, 0, "blank_text");
        }

        String cleaned = preprocess(text);
        if (StringUtils.isBlank(cleaned)) {
            return new LanguageDetectionResult("-", "-", 0D, 0, "empty_after_preprocess");
        }

        String sampled = sampleText(cleaned, 500);
        DetectedLanguage result = langId.classify(sampled, true);
        if (result == null || StringUtils.isBlank(result.langCode)) {
            return new LanguageDetectionResult("-", "-", 0D, 0, "langid_no_result");
        }

        String rawLang = result.langCode;
        double confidence = result.confidence;

        if ("zh".equals(rawLang)) {
            return new LanguageDetectionResult(detectChineseVariant(sampled), rawLang, confidence, 0, "chinese_variant_detected");
        }

        if ("tl".equalsIgnoreCase(rawLang) || "fil".equalsIgnoreCase(rawLang)) {
            return new LanguageDetectionResult("fil", rawLang, confidence, FILIPINO_SCORE_THRESHOLD, "raw_lang_is_filipino");
        }

        int filipinoScore = calculateFilipinoScore(sampled);
        if (shouldOverrideToFilipino(sampled, rawLang, filipinoScore)) {
            return new LanguageDetectionResult("fil", rawLang, confidence, filipinoScore, "filipino_score_override");
        }

        return new LanguageDetectionResult(convertToIso6393(rawLang), rawLang, confidence, filipinoScore, "raw_lang_kept");
    }

    private boolean shouldOverrideToFilipino(String text, String rawLang, int filipinoScore) {
        if (!("id".equalsIgnoreCase(rawLang) || "ms".equalsIgnoreCase(rawLang) || "en".equalsIgnoreCase(rawLang))) {
            return false;
        }
        if (text == null || text.length() < FILIPINO_MIN_TEXT_LENGTH) {
            return false;
        }
        return filipinoScore >= FILIPINO_SCORE_THRESHOLD;
    }

    private int calculateFilipinoScore(String text) {
        String normalized = " " + text.toLowerCase() + " ";
        int score = 0;

        for (String marker : FILIPINO_STRONG_MARKERS) {
            if (containsWord(normalized, marker)) {
                score += 2;
            }
        }
        for (String marker : FILIPINO_MEDIUM_MARKERS) {
            if (containsWord(normalized, marker)) {
                score += 1;
            }
        }
        return score;
    }

    private boolean containsWord(String text, String word) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        return matcher.find();
    }

    private String preprocess(String text) {
        text = URL_PATTERN.matcher(text).replaceAll(" ");
        text = EMAIL_PATTERN.matcher(text).replaceAll(" ");
        text = MENTION_PATTERN.matcher(text).replaceAll(" ");
        text = HASHTAG_PATTERN.matcher(text).replaceAll(" ");
        text = text.replaceAll("[\\x{1F300}-\\x{1F9FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}]", " ");
        text = WHITESPACE_PATTERN.matcher(text).replaceAll(" ").trim();
        return text;
    }

    private String sampleText(String text, int maxLength) {
        int len = text.length();
        if (len <= maxLength) {
            return text;
        }
        int headLen = maxLength / 5;
        int middleLen = maxLength * 3 / 5;
        int tailLen = maxLength / 5;

        StringBuilder sb = new StringBuilder(maxLength + 10);
        sb.append(text, 0, headLen);
        int middleStart = (len - middleLen) / 2;
        sb.append(" ").append(text, middleStart, middleStart + middleLen);
        sb.append(" ").append(text, len - tailLen, len);
        return sb.toString();
    }

    private String convertToIso6393(String langCode) {
        return ISO_639_1_TO_3.getOrDefault(langCode, langCode);
    }

    private String detectChineseVariant(String text) {
        String simplified = ZhConverterUtil.toSimple(text);
        if (simplified == null || simplified.length() != text.length()) {
            return "zh-Hans";
        }

        int chineseCount = 0;
        int diffCount = 0;
        int i = 0;
        int j = 0;
        while (i < text.length() && j < simplified.length()) {
            int originalCp = text.codePointAt(i);
            int convertedCp = simplified.codePointAt(j);
            if (!Character.isSupplementaryCodePoint(originalCp) && isChinese((char) originalCp)) {
                chineseCount++;
                if (originalCp != convertedCp) {
                    diffCount++;
                }
            }
            i += Character.charCount(originalCp);
            j += Character.charCount(convertedCp);
        }

        if (chineseCount == 0) {
            return "zh-Hans";
        }
        double ratio = (double) diffCount / chineseCount;
        return ratio > TRADITIONAL_THRESHOLD ? "zh-Hant" : "zh-Hans";
    }

    private boolean isChinese(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x2E80 && c <= 0x2EFF)
                || (c >= 0x31C0 && c <= 0x31EF);
    }
}
