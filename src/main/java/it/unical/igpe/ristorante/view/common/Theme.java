package it.unical.igpe.ristorante.view.common;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.prefs.Preferences;

import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

/**
 * Aspetto dell'applicazione.
 *
 * Swing di serie ha un aspetto datato. FlatLaf è un Look and Feel: sostituisce
 * il modo in cui i componenti standard vengono DISEGNATI, senza cambiare di una
 * riga il codice che li usa. Restano JButton, JPanel, JTable: la logica del
 * progetto è quella vista a lezione, cambia solo la resa a schermo.
 *
 * Qui si imposta anche il font e una serie di parametri globali (arrotondamenti,
 * altezza delle righe, spessore del focus) attraverso UIManager, che è la
 * tabella di configurazione condivisa da tutti i componenti Swing.
 *
 * La scelta fra tema chiaro e scuro viene ricordata fra un avvio e l'altro con
 * java.util.prefs: è una preferenza di chi usa QUESTA macchina, non un dato del
 * ristorante, quindi non ha senso salvarla nel database condiviso.
 */
public final class Theme {

    /**
     * Font preferiti, in ordine: si usa il primo effettivamente installato.
     * Su Windows sarà Segoe UI, su macOS SF Pro, su Linux Carlito o DejaVu.
     * Senza questa lista, Swing ripiegherebbe su Dialog, che è bruttissimo.
     */
    private static final String[] FONT_PREFERENCES = {
            "Segoe UI", "Inter", "SF Pro Text", "Helvetica Neue",
            "Carlito", "Noto Sans", "DejaVu Sans", "Dialog"
    };

    /**
     * Stesso criterio per il font a spaziatura fissa. Indicarne uno solo non
     * basta: "DejaVu Sans Mono" su Windows non c'è, e Java ripiega su un font
     * proporzionale, con le cifre dei cronometri che cambiano larghezza.
     */
    private static final String[] MONO_PREFERENCES = {
            "JetBrains Mono", "Cascadia Mono", "Consolas", "SF Mono", "Menlo",
            "DejaVu Sans Mono", "Liberation Mono"
    };

    /** Chiave delle preferenze in cui si ricorda il tema scelto. */
    private static final String PREF_DARK = "tema.scuro";

    private static String fontFamily = "Dialog";
    private static String monoFamily = Font.MONOSPACED;
    private static boolean dark = true;

    private Theme() {
    }

    /** Applica il tema scelto l'ultima volta; al primo avvio quello scuro. */
    public static void applySaved() {
        apply(loadPreference());
    }

    /** Passa all'altro tema e ricorda la scelta per i prossimi avvii. */
    public static void toggle() {
        boolean next = !dark;
        savePreference(next);
        apply(next);
    }

    public static void apply(boolean useDarkTheme) {
        dark = useDarkTheme;
        try {
            if (useDarkTheme) {
                UIManager.setLookAndFeel(new FlatMacDarkLaf());
                Palette.applyDark();
            } else {
                UIManager.setLookAndFeel(new FlatMacLightLaf());
                Palette.applyLight();
            }
        } catch (Exception e) {
            // Se il Look and Feel non si carica l'applicazione deve comunque
            // partire: si prosegue con quello predefinito di Swing.
            System.err.println("Look and Feel non applicato: " + e.getMessage());
        }

        Set<String> installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        fontFamily = firstInstalled(FONT_PREFERENCES, installed, "Dialog");
        monoFamily = firstInstalled(MONO_PREFERENCES, installed, Font.MONOSPACED);
        UIManager.put("defaultFont", new FontUIResource(fontFamily, Font.PLAIN, 13));

        // Arrotondamenti e spaziature: sono le stesse proprietà che FlatLaf
        // legge per disegnare ogni componente.
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 1);
        UIManager.put("Button.innerFocusWidth", 1);

        UIManager.put("Table.rowHeight", 30);
        UIManager.put("Table.showHorizontalLines", Boolean.TRUE);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 1));
        UIManager.put("TableHeader.height", 32);
        UIManager.put("TabbedPane.tabHeight", 38);
        UIManager.put("TabbedPane.showTabSeparators", Boolean.TRUE);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.thumbArc", 8);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("TitlePane.unifiedBackground", Boolean.TRUE);

        UIManager.put("Panel.background", Palette.BG);
        UIManager.put("Component.borderColor", Palette.BORDER);
        UIManager.put("Component.accentColor", Palette.ACCENT);

        FlatLaf.updateUI();
    }

    /** Primo nome della lista effettivamente installato sul sistema. */
    private static String firstInstalled(String[] candidates, Set<String> installed, String fallback) {
        for (String candidate : candidates) {
            if (installed.contains(candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    private static boolean loadPreference() {
        try {
            return Preferences.userNodeForPackage(Theme.class).getBoolean(PREF_DARK, true);
        } catch (RuntimeException e) {
            // preferenze non disponibili (permessi, sistema particolare): tema scuro
            return true;
        }
    }

    private static void savePreference(boolean value) {
        try {
            Preferences.userNodeForPackage(Theme.class).putBoolean(PREF_DARK, value);
        } catch (RuntimeException e) {
            // senza preferenze la scelta vale solo fino alla chiusura
        }
    }

    public static boolean isDark() {
        return dark;
    }

    public static String getFontFamily() {
        return fontFamily;
    }

    public static Font font(int style, int size) {
        return new Font(fontFamily, style, size);
    }

    public static Font regular(int size) {
        return font(Font.PLAIN, size);
    }

    public static Font bold(int size) {
        return font(Font.BOLD, size);
    }

    /** Font a spaziatura fissa: numeri di tavolo, orari, contatori. */
    public static Font mono(int style, int size) {
        return new Font(monoFamily, style, size);
    }
}
