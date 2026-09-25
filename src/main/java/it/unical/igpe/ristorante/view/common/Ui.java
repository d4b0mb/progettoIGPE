package it.unical.igpe.ristorante.view.common;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Piccola libreria di componenti già impostati.
 *
 * Serve a evitare che ogni schermata reimposti font, colore e margini a mano:
 * dieci righe ripetute venti volte diventano venti punti in cui l'aspetto può
 * divergere. Qui si scrivono una volta sola.
 */
public final class Ui {

    private Ui() {
    }

    // --- testo -------------------------------------------------------------

    public static JLabel h1(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.bold(22));
        label.setForeground(Palette.TEXT);
        return label;
    }

    public static JLabel h2(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.bold(15));
        label.setForeground(Palette.TEXT);
        return label;
    }

    /** Intestazione di sezione: piccola, maiuscola, spaziata. */
    public static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text.toUpperCase());
        label.setFont(Theme.bold(11));
        label.setForeground(Palette.TEXT_MUTED);
        return label;
    }

    public static JLabel body(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.regular(13));
        label.setForeground(Palette.TEXT);
        return label;
    }

    public static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.regular(12));
        label.setForeground(Palette.TEXT_MUTED);
        return label;
    }

    public static JLabel value(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.bold(13));
        label.setForeground(Palette.TEXT);
        return label;
    }

    // --- pulsanti ----------------------------------------------------------

    /** Pulsante dell'azione principale della schermata: ce n'è uno solo per vista. */
    public static JButton primary(String text) {
        JButton button = new JButton(text);
        button.setFont(Theme.bold(13));
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setBackground(Palette.ACCENT);
        button.setForeground(Palette.ON_ACCENT);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
        return button;
    }

    public static JButton secondary(String text) {
        JButton button = new JButton(text);
        button.setFont(Theme.regular(13));
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setBackground(Palette.SURFACE_2);
        button.setForeground(Palette.TEXT);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(9, 16, 9, 16));
        return button;
    }

    public static JButton danger(String text) {
        JButton button = secondary(text);
        button.setForeground(Palette.DANGER);
        return button;
    }

    public static JButton toolButton(String text) {
        JButton button = new JButton(text);
        button.setFont(Theme.regular(12));
        button.setFocusPainted(false);
        button.setBackground(Palette.SURFACE_2);
        button.setForeground(Palette.TEXT);
        button.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        return button;
    }

    // --- contenitori -------------------------------------------------------

    public static JPanel column() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    public static JPanel row() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        return panel;
    }

    public static JPanel rowRight() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        panel.setOpaque(false);
        return panel;
    }

    public static JPanel transparent() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    public static Component gap(int size) {
        return Box.createRigidArea(new Dimension(size, size));
    }

    public static Component hGap(int size) {
        return Box.createRigidArea(new Dimension(size, 1));
    }

    public static Component vGap(int size) {
        return Box.createRigidArea(new Dimension(1, size));
    }

    public static JSeparator separator() {
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setForeground(Palette.BORDER);
        separator.setBackground(Palette.BORDER);
        return separator;
    }

    /** Aggiunge un margine interno a un componente esistente. */
    public static <T extends JComponent> T pad(T component, int top, int left, int bottom, int right) {
        component.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
        return component;
    }

    public static <T extends JComponent> T pad(T component, int all) {
        return pad(component, all, all, all, all);
    }

    /** Allinea a sinistra un componente dentro un BoxLayout verticale. */
    public static <T extends JComponent> T alignLeft(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    /**
     * Testo che va a capo dentro una JLabel, entro la larghezza indicata.
     *
     * ATTENZIONE: il classico &lt;body style='width:250px'&gt; NON funziona.
     * Il motore HTML di Swing ignora la larghezza dichiarata in uno stile e
     * produce una sola riga lunghissima, che poi viene tagliata. L'unica forma
     * che rispetta davvero il vincolo è una cella di tabella con l'attributo
     * width, ed è il motivo per cui esiste questo metodo invece di scrivere
     * l'HTML a mano ogni volta.
     */
    public static String wrapped(String text, int widthPixels) {
        return "<html><table cellpadding=0 cellspacing=0><tr><td width=" + widthPixels + ">"
                + text + "</td></tr></table></html>";
    }

    /** Coppia etichetta + valore, incolonnata verticalmente. */
    public static JPanel field(String label, String value) {
        JPanel panel = column();
        JLabel caption = muted(label);
        caption.setFont(Theme.regular(11));
        panel.add(alignLeft(caption));
        panel.add(vGap(2));
        panel.add(alignLeft(value(value == null || value.isBlank() ? "—" : value)));
        return panel;
    }

    // --- tastiera ----------------------------------------------------------

    /**
     * Collega una scorciatoia da tastiera a un'azione.
     *
     * Si usano InputMap e ActionMap (i "key bindings" di Swing) invece di un
     * KeyListener: il KeyListener riceve i tasti solo se proprio quel
     * componente ha il focus, mentre qui si sceglie la condizione, ad esempio
     * WHEN_IN_FOCUSED_WINDOW = "ovunque sia il focus, dentro questa finestra".
     *
     * @param keyStroke nel formato di KeyStroke.getKeyStroke, es. "ctrl Z"
     */
    public static void bindKey(JComponent component, int condition, String keyStroke,
                               String name, Runnable action) {
        component.getInputMap(condition).put(KeyStroke.getKeyStroke(keyStroke), name);
        component.getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    // --- campi numerici / data ----------------------------------------------

    /**
     * Seleziona tutto il testo quando il campo prende il focus.
     *
     * Senza questo, cliccare in un campo che mostra già un valore (una data,
     * un'ora, un numero) e digitare subito lascia il cursore dov'era: il
     * nuovo carattere si aggiunge accanto al vecchio invece di sostituirlo.
     * Selezionare tutto all'ingresso fa sì che la prima battuta ricominci da
     * zero, come in un vero selettore di data o in un campo numerico.
     */
    public static void selectAllOnFocus(JFormattedTextField field) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                SwingUtilities.invokeLater(field::selectAll);
            }
        });
    }

    /** Come {@link #selectAllOnFocus(JFormattedTextField)}, per il campo di testo di uno spinner. */
    public static void selectAllOnFocus(JSpinner spinner) {
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            selectAllOnFocus(editor.getTextField());
        }
    }
}
