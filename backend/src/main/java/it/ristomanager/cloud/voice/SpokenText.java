package it.ristomanager.cloud.voice;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns screen-style text into text that is good to hear: clock times, dates, counts, amounts and phone numbers become
 * words, and symbols such as "&amp;" or a dash used as a separator stop being read out. It works on text only, so it fits
 * any speech engine, and it knows Italian and English (anything else is treated as English, like the voice choice).
 *
 * The rule is to never change the meaning: a pattern that is not clearly one of the known shapes (for example "8/10",
 * which could be a date or a score) is left exactly as it was. The text is never logged here.
 */
final class SpokenText {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String PREPS = "entro le|verso le|dalle|alle|delle|nelle|sulle|le|ore";
    private static final String NOT_IN_NUMBER = "[\\p{L}\\d.,:/+-]";

    private static final String TIME_RANGE =
            "(?<![\\d:.])(?<rh1>\\d{1,2}):(?<rm1>\\d{2})\\s*[-\u2013\u2014]\\s*(?<rh2>\\d{1,2}):(?<rm2>\\d{2})(?!\\d|:\\d|\\.\\d)";
    private static final String TIME =
            "(?:(?<![\\p{L}\\d:.])(?<tp>" + PREPS + ")\\s+)?(?<![\\d:.])(?<th>\\d{1,2}):(?<tm>\\d{2})(?!\\d|:\\d|\\.\\d)";
    private static final String ISO_DATE =
            "(?<![\\d/.-])(?<iy>\\d{4})-(?<im>\\d{2})-(?<id>\\d{2})(?![\\d/-]|\\.\\d)";
    private static final String DATE =
            "(?<![\\d/.-])(?<dd>\\d{1,2})/(?<dm>\\d{1,2})(?:/(?<dy>\\d{4}|\\d{2}))?(?![\\d/]|\\.\\d)";
    private static final String MONEY_SIGN = "\u20ac\\s?(?<ma>\\d+(?:[.,]\\d+)*)(?!\\d)";
    private static final String MONEY_WORD =
            "(?<![\\p{L}\\d.,])(?<mb>\\d+(?:[.,]\\d+)*)\\s?(?:\u20ac|euro\\b|eur\\b)";
    private static final String PERCENT = "(?<![\\p{L}\\d.,])(?<pc>\\d+(?:[.,]\\d+)?)\\s?%";
    private static final String NUMBER_RANGE =
            "(?<!" + NOT_IN_NUMBER + ")(?<!\\d[ .-])(?<ra>\\d{1,3})[-\u2013](?<rb>\\d{1,3})(?![\\p{L}\\d]|[ .-]\\d|[.,:/]\\d)";
    private static final String PHONE =
            "(?<!" + NOT_IN_NUMBER + ")(?<ph>\\+?\\d{2,4}(?:[ .-]\\d{1,8}){1,4}|\\+\\d{8,15}|\\d{8,15})(?![\\p{L}\\d]|[.,:/-]\\d)";
    private static final String NUMBER =
            "(?<![\\p{L}\\d.,])(?<nu>\\d+(?:[.,]\\d+)*)(?![\\p{L}\\d]|[.,]\\d)";

    private static final Pattern WITH_PHONE = Pattern.compile(String.join("|",
            TIME_RANGE, TIME, ISO_DATE, DATE, MONEY_SIGN, MONEY_WORD, PERCENT, NUMBER_RANGE, PHONE, NUMBER), FLAGS);
    private static final Pattern WITHOUT_PHONE = Pattern.compile(String.join("|",
            TIME_RANGE, TIME, ISO_DATE, DATE, MONEY_SIGN, MONEY_WORD, PERCENT, NUMBER_RANGE, NUMBER), FLAGS);
    private static final Pattern NEXT_WORD = Pattern.compile("^\\s+(\\p{L}+)");

    private static final Set<String> IT_MONTH_NAMES = Set.of("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
            "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre");
    /** Nouns that follow "1" in staff talk, so "1 tavolo" is said "un tavolo". Other words after a 1 keep "uno". */
    private static final Set<String> IT_MASCULINE = Set.of("tavolo", "coperto", "ordine", "piatto", "cliente", "ospite",
            "bambino", "seggiolone", "minuto", "giorno", "posto", "gruppo", "turno", "amico", "tavolino", "antipasto",
            "dolce", "vino", "caff\u00e8", "conto");
    private static final Set<String> IT_FEMININE = Set.of("persona", "prenotazione", "sala", "pizza", "portata", "sedia",
            "bottiglia", "bambina", "porzione", "volta", "ordinazione", "richiesta", "nota", "allergia", "birra",
            "settimana", "serata", "cena", "terrazza", "ora");

    private static final String[] IT_UNITS = {"zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto",
            "nove", "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto",
            "diciannove"};
    private static final String[] IT_TENS = {"", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta",
            "ottanta", "novanta"};
    private static final String[] IT_MONTHS = {"gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio",
            "agosto", "settembre", "ottobre", "novembre", "dicembre"};

    private static final String[] EN_UNITS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen"};
    private static final String[] EN_TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
            "ninety"};
    private static final String[] EN_ORDINAL_UNITS = {"", "first", "second", "third", "fourth", "fifth", "sixth",
            "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
            "sixteenth", "seventeenth", "eighteenth", "nineteenth"};
    private static final String[] EN_MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};

    private final boolean italian;

    private SpokenText(boolean italian) {
        this.italian = italian;
    }

    /**
     * The text to hand to a speech engine. {@code languageTag} is "it" (or "it-IT") for Italian; anything else, or none,
     * is English, the same split the voice choice uses. Text that has nothing to change comes back as it was.
     */
    static String forSpeech(String text, String languageTag) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        boolean italian = "it".equals(VoiceHttp.primaryLanguage(languageTag));
        String result = new SpokenText(italian).convert(text);
        return result.isBlank() ? text : result;
    }

    private String convert(String text) {
        String plain = text.replaceAll("[*`#~]", "").replace('_', ' ');
        String words = rewrite(plain, WITH_PHONE);
        return words.replaceAll("\\s{2,}", " ").replaceAll("\\s+([,.;:!?])", "$1").strip();
    }

    /** Replaces every known shape found by {@code pattern}; the text between them only loses its separator symbols. */
    private String rewrite(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 32);
        int last = 0;
        while (m.find()) {
            out.append(symbols(text.substring(last, m.start())));
            out.append(replacement(m, text));
            last = m.end();
        }
        out.append(symbols(text.substring(last)));
        return out.toString();
    }

    /** "&amp;" is "and", a dash with spaces around it is a pause, "e/o" is two words. */
    private String symbols(String gap) {
        String s = gap.replace("&", italian ? " e " : " and ");
        s = s.replaceAll("\\s[-\u2013\u2014]+\\s", ", ");
        s = s.replaceAll("(?<=\\p{L})/(?=\\p{L})", " ");
        return s;
    }

    private String replacement(Matcher m, String text) {
        String all = m.group();
        String spoken;
        if (m.group("rh1") != null) {
            spoken = timeRange(num(m, "rh1"), num(m, "rm1"), num(m, "rh2"), num(m, "rm2"));
        } else if (m.group("th") != null) {
            spoken = time(m.group("tp"), num(m, "th"), num(m, "tm"));
        } else if (m.group("iy") != null) {
            spoken = date(num(m, "id"), num(m, "im"), num(m, "iy"));
        } else if (m.group("dd") != null) {
            spoken = slashDate(m);
        } else if (m.group("ma") != null) {
            spoken = money(m.group("ma"));
        } else if (m.group("mb") != null) {
            spoken = money(m.group("mb"));
        } else if (m.group("pc") != null) {
            String n = plainNumber(m.group("pc"));
            spoken = n == null ? null : n + (italian ? " per cento" : " percent");
        } else if (m.group("ra") != null) {
            spoken = (italian ? "da " : "") + cardinal(num(m, "ra")) + (italian ? " a " : " to ") + cardinal(num(m, "rb"));
        } else if (hasGroup(m, "ph") && m.group("ph") != null) {
            spoken = phone(m.group("ph"));
            if (spoken == null) {
                return rewrite(all, WITHOUT_PHONE);
            }
        } else {
            spoken = integerOrDecimal(m.group("nu"), text, m.end());
        }
        return spoken == null ? all : spoken;
    }

    private static boolean hasGroup(Matcher m, String name) {
        try {
            m.start(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int num(Matcher m, String group) {
        return Integer.parseInt(m.group(group));
    }

    // ---- times

    private String timeRange(int h1, int m1, int h2, int m2) {
        if (!validTime(h1, m1) || !validTime(h2, m2)) {
            return null;
        }
        if (italian) {
            return itHourPhrase("dalle", h1, m1) + " " + itHourPhrase("alle", h2, m2);
        }
        boolean samePart = enMeridiem(h1, m1) != null && enMeridiem(h1, m1).equals(enMeridiem(h2, m2));
        return "from " + enTime(h1, m1, !samePart) + " to " + enTime(h2, m2, true);
    }

    private String time(String prep, int h, int min) {
        if (!validTime(h, min)) {
            return null;
        }
        if (italian) {
            return itHourPhrase(prep == null ? "le" : prep.toLowerCase(Locale.ROOT), h, min);
        }
        return (prep == null ? "" : prep + " ") + enTime(h, min, true);
    }

    private static boolean validTime(int h, int min) {
        return h <= 24 && min < 60 && (h < 24 || min == 0);
    }

    /** "dalle dodici", "alle tredici e quarantacinque", "l'una", "ore venti e trenta"; minutes 00 are not said. */
    private String itHourPhrase(String prep, int h, int min) {
        String hour;
        String lead;
        if (h == 1) {
            hour = "una";
            lead = "ore".equals(prep) ? "ore " : prep.replaceAll("e$", "") + "'";
        } else {
            hour = cardinal(h);
            lead = prep + " ";
        }
        return lead + hour + (min == 0 ? "" : " e " + cardinal(min));
    }

    private static String enMeridiem(int h, int min) {
        if (min == 0 && (h == 0 || h == 24 || h == 12)) {
            return null;
        }
        return h >= 12 && h < 24 ? "p.m." : "a.m.";
    }

    /** "eight thirty p.m.", "eight oh five p.m.", "noon", "midnight". */
    private String enTime(int h, int min, boolean withMeridiem) {
        if (min == 0 && (h == 0 || h == 24)) {
            return "midnight";
        }
        if (min == 0 && h == 12) {
            return "noon";
        }
        int h12 = h % 12 == 0 ? 12 : h % 12;
        String minutes = min == 0 ? "" : (min < 10 ? " oh " : " ") + cardinal(min);
        String suffix = withMeridiem ? " " + enMeridiem(h, min) : "";
        return cardinal(h12) + minutes + suffix;
    }

    // ---- dates

    private String slashDate(Matcher m) {
        int day = num(m, "dd");
        int month = num(m, "dm");
        String yearText = m.group("dy");
        if (yearText == null && (day <= 12 || (day == 24 && month == 7))) {
            return null; // 8/10 may be a date or a score, 24/7 is "always open": leave them alone
        }
        int year = yearText == null ? 0 : (yearText.length() == 2 ? 2000 : 0) + Integer.parseInt(yearText);
        return date(day, month, yearText == null ? null : year);
    }

    private String date(int day, int month, Integer year) {
        try {
            LocalDate.of(year == null ? 2000 : year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
        if (italian) {
            return (day == 1 ? "primo" : cardinal(day)) + " " + IT_MONTHS[month - 1] + (year == null ? "" : " " + cardinal(year));
        }
        return EN_MONTHS[month - 1] + " " + enOrdinal(day) + (year == null ? "" : ", " + enYear(year));
    }

    private static String enOrdinal(int n) {
        if (n < 20) {
            return EN_ORDINAL_UNITS[n];
        }
        int unit = n % 10;
        return unit == 0 ? EN_TENS[n / 10].replace("y", "ieth") : EN_TENS[n / 10] + "-" + EN_ORDINAL_UNITS[unit];
    }

    private String enYear(int year) {
        if (year < 1000 || year > 2099) {
            return cardinal(year);
        }
        if (year >= 2000 && year < 2010) {
            return year == 2000 ? "two thousand" : "two thousand " + cardinal(year - 2000);
        }
        int high = year / 100;
        int low = year % 100;
        if (low == 0) {
            return cardinal(high) + " hundred";
        }
        return cardinal(high) + (low < 10 ? " oh " : " ") + cardinal(low);
    }

    // ---- money, numbers, phones

    /** "12,50" gives "twelve euros and fifty cents"; a value that cannot be read with certainty is left alone. */
    private String money(String amount) {
        int cut = Math.max(amount.lastIndexOf(','), amount.lastIndexOf('.'));
        int euros;
        int cents = 0;
        try {
            if (cut < 0) {
                euros = Integer.parseInt(amount);
            } else {
                int after = amount.length() - cut - 1;
                String whole = amount.substring(0, cut).replaceAll("[.,]", "");
                if (after == 3) {
                    euros = Integer.parseInt(whole + amount.substring(cut + 1));
                } else if (after <= 2) {
                    euros = Integer.parseInt(whole);
                    cents = Integer.parseInt(amount.substring(cut + 1)) * (after == 1 ? 10 : 1);
                } else {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (euros > 999_999) {
            return null;
        }
        if (italian) {
            if (euros == 0 && cents > 0) {
                return cardinal(cents) + (cents == 1 ? " centesimo" : " centesimi");
            }
            return (euros == 1 ? "un" : cardinal(euros)) + " euro" + (cents > 0 ? " e " + cardinal(cents) : "");
        }
        if (euros == 0 && cents > 0) {
            return cardinal(cents) + (cents == 1 ? " cent" : " cents");
        }
        return cardinal(euros) + (euros == 1 ? " euro" : " euros") + (cents > 0 ? " and " + cardinal(cents) + (cents == 1 ? " cent" : " cents") : "");
    }

    /** A whole number or a decimal, written the way the language writes it; null when it is neither. */
    private String plainNumber(String s) {
        if (s.matches("\\d+")) {
            return s.length() > 6 ? null : cardinal(Integer.parseInt(s));
        }
        String thousands = italian ? "\\d{1,3}(\\.\\d{3})+" : "\\d{1,3}(,\\d{3})+";
        if (s.matches(thousands)) {
            String digits = s.replaceAll("[.,]", "");
            return digits.length() > 6 ? null : cardinal(Integer.parseInt(digits));
        }
        String decimal = italian ? "\\d{1,6},\\d{1,6}" : "\\d{1,6}\\.\\d{1,6}";
        if (s.matches(decimal)) {
            String[] parts = s.split("[.,]");
            String fraction;
            if (italian) {
                String zeros = "0".repeat(parts[1].length() - parts[1].replaceFirst("^0+", "").length());
                String rest = parts[1].replaceFirst("^0+", "");
                fraction = (zeros.isEmpty() ? "" : "zero ".repeat(zeros.length())) + (rest.isEmpty() ? "" : cardinal(Integer.parseInt(rest)));
                fraction = fraction.strip();
            } else {
                fraction = digitsOneByOne(parts[1], " ");
            }
            return cardinal(Integer.parseInt(parts[0])) + (italian ? " virgola " : " point ") + fraction;
        }
        return null;
    }

    private String integerOrDecimal(String s, String text, int end) {
        String plain = plainNumber(s);
        if (plain != null && italian && s.equals("1")) {
            return itOne(text, end);
        }
        return plain;
    }

    /** "1" in Italian: "un tavolo", "una persona", "primo ottobre", otherwise "uno" (as in "tavolo uno"). */
    private String itOne(String text, int end) {
        Matcher next = NEXT_WORD.matcher(text.substring(end));
        if (!next.find()) {
            return "uno";
        }
        String word = next.group(1).toLowerCase(Locale.ROOT);
        if (IT_MONTH_NAMES.contains(word)) {
            return "primo";
        }
        if (IT_FEMININE.contains(word)) {
            return "una";
        }
        if (IT_MASCULINE.contains(word)) {
            return word.matches("^(z|gn|ps|x|y|s[^aeiou\\W]).*") ? "uno" : "un";
        }
        return "uno";
    }

    /** Digits said one at a time, in the groups they were written in ("333 123" gives "tre tre tre, uno due tre"). */
    private String phone(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return null;
        }
        List<String> groups = List.of(raw.replaceFirst("^\\+", "").split("[ .-]"));
        if (groups.stream().noneMatch(g -> g.length() >= 3)) {
            return null; // a few small numbers side by side, not a phone number
        }
        StringBuilder out = new StringBuilder(raw.startsWith("+") ? (italian ? "pi\u00f9 " : "plus ") : "");
        for (int i = 0; i < groups.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(digitsOneByOne(groups.get(i), " "));
        }
        return out.toString();
    }

    private String digitsOneByOne(String digits, String separator) {
        StringBuilder out = new StringBuilder();
        for (char c : digits.toCharArray()) {
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(italian ? IT_UNITS[c - '0'] : EN_UNITS[c - '0']);
        }
        return out.toString();
    }

    // ---- number words

    private String cardinal(int n) {
        return italian ? itCardinal(n) : enCardinal(n);
    }

    private static String itCardinal(int n) {
        if (n < 20) {
            return IT_UNITS[n];
        }
        if (n < 100) {
            String tens = IT_TENS[n / 10];
            int unit = n % 10;
            if (unit == 0) {
                return tens;
            }
            if (unit == 1 || unit == 8) {
                tens = tens.substring(0, tens.length() - 1);
            }
            return tens + (unit == 3 ? "tr\u00e9" : IT_UNITS[unit]);
        }
        if (n < 1000) {
            int rest = n % 100;
            String hundreds = n / 100 == 1 ? "cento" : IT_UNITS[n / 100] + "cento";
            if (rest == 0) {
                return hundreds;
            }
            if (rest >= 80 && rest < 90) {
                hundreds = hundreds.substring(0, hundreds.length() - 1);
            }
            return hundreds + itCardinal(rest);
        }
        int thousands = n / 1000;
        int rest = n % 1000;
        String head = thousands == 1 ? "mille" : itCardinal(thousands).replace('\u00e9', 'e') + "mila";
        return head + (rest == 0 ? "" : itCardinal(rest));
    }

    private static String enCardinal(int n) {
        if (n < 20) {
            return EN_UNITS[n];
        }
        if (n < 100) {
            return EN_TENS[n / 10] + (n % 10 == 0 ? "" : "-" + EN_UNITS[n % 10]);
        }
        if (n < 1000) {
            return EN_UNITS[n / 100] + " hundred" + (n % 100 == 0 ? "" : " " + enCardinal(n % 100));
        }
        return enCardinal(n / 1000) + " thousand" + (n % 1000 == 0 ? "" : " " + enCardinal(n % 1000));
    }
}
