package it.unical.igpe.ristorante.view.common;

import java.awt.Color;

import it.unical.igpe.ristorante.model.floor.ObstacleType;
import it.unical.igpe.ristorante.model.floor.TableStatus;
import it.unical.igpe.ristorante.model.kitchen.TicketPriority;
import it.unical.igpe.ristorante.model.kitchen.TicketStatus;

/**
 * Tutti i colori dell'applicazione, in un posto solo.
 *
 * Nessun'altra classe scrive "new Color(...)": se un colore comparisse anche
 * altrove, cambiare tema significherebbe andarlo a cercare in venti file. Qui
 * i campi non sono final proprio perché Theme.apply() li riscrive quando si
 * passa da tema scuro a tema chiaro.
 *
 * I colori di stato non sono decorativi: in cucina e in sala sono l'unica
 * informazione che si legge a distanza, quindi sono scelti per restare
 * distinguibili anche a colpo d'occhio.
 */
public final class Palette {

    private Palette() {
    }

    // --- superfici e testo -------------------------------------------------
    public static Color BG = new Color(0x14161B);
    public static Color SURFACE = new Color(0x1B1F27);
    public static Color SURFACE_2 = new Color(0x222732);
    public static Color SURFACE_3 = new Color(0x2A303C);
    public static Color BORDER = new Color(0x2F3644);
    public static Color TEXT = new Color(0xE8ECF3);
    public static Color TEXT_MUTED = new Color(0x98A2B3);

    // --- colori identitari -------------------------------------------------
    public static Color ACCENT = new Color(0xE0A458);
    public static Color ACCENT_SOFT = new Color(0x4A3B27);
    public static Color TEAL = new Color(0x4FB3A6);
    /** Testo sopra un fondo color accento (il pulsante principale). */
    public static Color ON_ACCENT = new Color(0x14161B);

    // --- semantica ---------------------------------------------------------
    public static Color OK = new Color(0x3FB950);
    public static Color WARN = new Color(0xE3A008);
    public static Color DANGER = new Color(0xF0533F);
    public static Color INFO = new Color(0x58A6FF);
    public static Color VIP = new Color(0xA371F7);

    // --- piantina ----------------------------------------------------------
    public static Color ROOM_FILL = new Color(0x171A21);
    public static Color GRID_MINOR = new Color(0x232935);
    public static Color GRID_MAJOR = new Color(0x2E3646);
    public static Color WALL = new Color(0x596274);
    public static Color ZONE_FILL = new Color(0x232A36);
    public static Color SELECTION = new Color(0x58A6FF);

    static void applyDark() {
        BG = new Color(0x14161B);
        SURFACE = new Color(0x1B1F27);
        SURFACE_2 = new Color(0x222732);
        SURFACE_3 = new Color(0x2A303C);
        BORDER = new Color(0x2F3644);
        TEXT = new Color(0xE8ECF3);
        TEXT_MUTED = new Color(0x98A2B3);
        ACCENT = new Color(0xE0A458);
        ACCENT_SOFT = new Color(0x4A3B27);
        TEAL = new Color(0x4FB3A6);
        ON_ACCENT = new Color(0x14161B);
        OK = new Color(0x3FB950);
        WARN = new Color(0xE3A008);
        DANGER = new Color(0xF0533F);
        INFO = new Color(0x58A6FF);
        VIP = new Color(0xA371F7);
        ROOM_FILL = new Color(0x171A21);
        GRID_MINOR = new Color(0x232935);
        GRID_MAJOR = new Color(0x2E3646);
        WALL = new Color(0x596274);
        ZONE_FILL = new Color(0x232A36);
        SELECTION = new Color(0x58A6FF);
    }

    static void applyLight() {
        BG = new Color(0xF2F4F7);
        SURFACE = new Color(0xFFFFFF);
        SURFACE_2 = new Color(0xF6F8FA);
        SURFACE_3 = new Color(0xEBEFF4);
        BORDER = new Color(0xD5DBE3);
        TEXT = new Color(0x1A1F27);
        TEXT_MUTED = new Color(0x5B6673);
        ACCENT = new Color(0xB07226);
        ACCENT_SOFT = new Color(0xF6E7CF);
        TEAL = new Color(0x1F8A7C);
        ON_ACCENT = new Color(0x14161B);
        OK = new Color(0x1F883D);
        WARN = new Color(0xB07C00);
        DANGER = new Color(0xCF2A20);
        INFO = new Color(0x1668C7);
        VIP = new Color(0x7B4BD1);
        ROOM_FILL = new Color(0xFBFCFD);
        GRID_MINOR = new Color(0xE7EBF0);
        GRID_MAJOR = new Color(0xD3DAE3);
        WALL = new Color(0x8A94A3);
        ZONE_FILL = new Color(0xEDF1F6);
        SELECTION = new Color(0x1668C7);
    }

    // ----------------------------------------------------------------------
    // Colori derivati dallo stato del dominio
    // ----------------------------------------------------------------------

    public static Color forTableStatus(TableStatus status) {
        return switch (status) {
            case LIBERO -> OK;
            case PRENOTATO -> WARN;
            case OCCUPATO -> DANGER;
            case DA_PULIRE -> INFO;
            case FUORI_SERVIZIO -> TEXT_MUTED;
        };
    }

    public static Color forPriority(TicketPriority priority) {
        return switch (priority) {
            case NORMALE -> INFO;
            case ALTA -> WARN;
            case URGENTE -> DANGER;
            case VIP -> VIP;
        };
    }

    public static Color forTicketStatus(TicketStatus status) {
        return switch (status) {
            case NUOVA -> INFO;
            case IN_PREPARAZIONE -> WARN;
            case PRONTA -> OK;
            case SERVITA -> TEXT_MUTED;
            case ANNULLATA -> DANGER;
        };
    }

    public static Color forObstacle(ObstacleType type) {
        return switch (type) {
            case MURO, COLONNA, SCALE -> WALL;
            case PORTA, FINESTRA -> TEAL;
            case CUCINA -> new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 70);
            case BAGNO -> new Color(INFO.getRed(), INFO.getGreen(), INFO.getBlue(), 55);
            case BAR -> new Color(VIP.getRed(), VIP.getGreen(), VIP.getBlue(), 55);
            case INGRESSO -> new Color(OK.getRed(), OK.getGreen(), OK.getBlue(), 55);
            case DEPOSITO -> new Color(TEXT_MUTED.getRed(), TEXT_MUTED.getGreen(), TEXT_MUTED.getBlue(), 45);
        };
    }

    /** Codice esadecimale di un colore, per i testi in HTML dentro le JLabel. */
    public static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** Versione traslucida di un colore, per riempimenti e sfondi di etichetta. */
    public static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Mescola due colori; ratio 0 = primo colore, 1 = secondo. */
    public static Color mix(Color a, Color b, double ratio) {
        double r = Math.max(0, Math.min(1, ratio));
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * r),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * r),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * r));
    }
}
