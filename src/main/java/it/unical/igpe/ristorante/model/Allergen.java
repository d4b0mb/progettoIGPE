package it.unical.igpe.ristorante.model;

/**
 * I 14 allergeni a dichiarazione obbligatoria (Reg. UE 1169/2011).
 *
 * Sono un enum e non delle semplici stringhe perché devono essere confrontabili,
 * memorizzabili in un EnumSet (compatto e velocissimo) e soprattutto non
 * possono essere scritti male da chi inserisce la comanda: un errore di
 * battitura su "arachidi" in cucina è un problema serio.
 */
public enum Allergen {

    GLUTINE("Glutine", "GLU"),
    CROSTACEI("Crostacei", "CRO"),
    UOVA("Uova", "UOV"),
    PESCE("Pesce", "PES"),
    ARACHIDI("Arachidi", "ARA"),
    SOIA("Soia", "SOI"),
    LATTE("Latte e derivati", "LAT"),
    FRUTTA_A_GUSCIO("Frutta a guscio", "FRG"),
    SEDANO("Sedano", "SED"),
    SENAPE("Senape", "SEN"),
    SESAMO("Semi di sesamo", "SES"),
    SOLFITI("Anidride solforosa e solfiti", "SOL"),
    LUPINI("Lupini", "LUP"),
    MOLLUSCHI("Molluschi", "MOL");

    private final String label;
    private final String shortCode;

    Allergen(String label, String shortCode) {
        this.label = label;
        this.shortCode = shortCode;
    }

    public String getLabel() { return label; }

    public String getShortCode() { return shortCode; }

    @Override
    public String toString() {
        return label;
    }
}
