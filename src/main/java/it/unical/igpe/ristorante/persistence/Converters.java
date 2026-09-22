package it.unical.igpe.ristorante.persistence;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import it.unical.igpe.ristorante.model.Allergen;

/**
 * Conversioni fra i tipi Java del modello e le colonne di testo di SQLite.
 *
 * SQLite non ha un tipo data/ora nativo: le date si salvano come stringhe in
 * formato ISO-8601 ("2026-08-29T20:30"), che ha il pregio di essere ordinabile
 * alfabeticamente esattamente come lo è cronologicamente.
 */
public final class Converters {

    private Converters() {
    }

    public static String toText(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    public static LocalDateTime toDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Un insieme di allergeni diventa "GLUTINE,LATTE"; l'insieme vuoto diventa "". */
    public static String toText(Set<Allergen> allergens) {
        if (allergens == null || allergens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Allergen a : allergens) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(a.name());
        }
        return sb.toString();
    }

    public static Set<Allergen> toAllergens(String text) {
        Set<Allergen> result = EnumSet.noneOf(Allergen.class);
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String part : text.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                result.add(Allergen.valueOf(token));
            } catch (IllegalArgumentException e) {
                // valore non più esistente nell'enum: viene ignorato invece di
                // far fallire il caricamento di tutta la prenotazione
            }
        }
        return result;
    }

    /**
     * Conversione difensiva verso un enum: se il testo nel database non
     * corrisponde a nessuna costante si ritorna il valore di riserva.
     */
    public static <E extends Enum<E>> E toEnum(Class<E> type, String text, E fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, text.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
