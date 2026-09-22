package it.unical.igpe.ristorante.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import it.unical.igpe.ristorante.AppConfig;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.User;
import it.unical.igpe.ristorante.persistence.DataAccessException;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Schermata di accesso.
 *
 * È divisa in due: a sinistra un pannello decorativo disegnato a mano con
 * Graphics2D (una piantina stilizzata), a destra il modulo di login. Il
 * pannello di sinistra è anche una dimostrazione in piccolo di ciò che fa
 * l'editor di sala: si ridisegna da solo a ogni ridimensionamento perché le
 * posizioni sono calcolate in proporzione, non in pixel fissi.
 */
public class LoginFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final RestaurantModel model;
    private final JTextField usernameField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JLabel errorLabel = new JLabel(" ");
    private final JButton loginButton = Ui.primary("Accedi");

    public LoginFrame(RestaurantModel model) {
        this.model = model;

        setTitle("RistoManager — Accesso (" + AppConfig.getStation() + ")");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(880, 560));
        setSize(940, 600);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Palette.BG);
        root.add(new BrandPanel(), BorderLayout.WEST);
        root.add(buildForm(), BorderLayout.CENTER);
        setContentPane(root);

        // Invio in qualunque campo preme "Accedi", perché è il pulsante
        // predefinito della finestra. Non va aggiunto ANCHE un KeyListener sui
        // campi: ogni Invio farebbe partire l'accesso due volte, e il secondo
        // tentativo (a password ormai svuotata) sostituirebbe "credenziali non
        // valide" con un fuorviante "inserisci nome utente e password".
        getRootPane().setDefaultButton(loginButton);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                usernameField.requestFocusInWindow();
            }
        });
    }

    private JPanel buildForm() {
        JPanel container = new JPanel(new GridBagLayout());
        container.setBackground(Palette.BG);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setPreferredSize(new Dimension(320, 460));

        JLabel welcome = new JLabel("Bentornato");
        welcome.setFont(Theme.bold(26));
        welcome.setForeground(Palette.TEXT);

        JLabel subtitle = Ui.muted(Ui.wrapped(
                "Accedi con le credenziali fornite dal responsabile di sala.", 320));
        subtitle.setFont(Theme.regular(13));

        JLabel station = Ui.muted("Postazione: " + AppConfig.getStation());
        station.setFont(Theme.bold(12));

        form.add(Ui.alignLeft(welcome));
        form.add(Ui.vGap(6));
        form.add(Ui.alignLeft(subtitle));
        form.add(Ui.vGap(4));
        form.add(Ui.alignLeft(station));
        form.add(Ui.vGap(24));

        form.add(Ui.alignLeft(fieldLabel("Nome utente")));
        form.add(Ui.vGap(6));
        styleField(usernameField);
        form.add(Ui.alignLeft(usernameField));
        form.add(Ui.vGap(16));

        form.add(Ui.alignLeft(fieldLabel("Password")));
        form.add(Ui.vGap(6));
        styleField(passwordField);
        form.add(Ui.alignLeft(passwordField));
        form.add(Ui.vGap(10));

        errorLabel.setFont(Theme.regular(12));
        errorLabel.setForeground(Palette.DANGER);
        form.add(Ui.alignLeft(errorLabel));
        form.add(Ui.vGap(10));

        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        loginButton.setPreferredSize(new Dimension(320, 42));
        loginButton.addActionListener(e -> onLogin());
        form.add(Ui.alignLeft(loginButton));

        form.add(Ui.vGap(26));
        form.add(Ui.alignLeft(Ui.separator()));
        form.add(Ui.vGap(16));
        form.add(Ui.alignLeft(Ui.sectionTitle("Utenti di prova · clic per compilare")));
        form.add(Ui.vGap(8));
        form.add(Ui.alignLeft(demoUser("admin", "admin123", "Tier 1 — accesso completo", Palette.ACCENT)));
        form.add(Ui.vGap(4));
        form.add(Ui.alignLeft(demoUser("mrossi", "mario123", "Tier 2 — prenotazioni e cucina", Palette.TEAL)));
        form.add(Ui.vGap(4));
        form.add(Ui.alignLeft(demoUser("stage", "stage123", "Tier 3 — sola lettura", Palette.TEXT_MUTED)));

        container.add(form);
        return container;
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.bold(12));
        label.setForeground(Palette.TEXT_MUTED);
        return label;
    }

    private void styleField(JTextField field) {
        field.setFont(Theme.regular(14));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        field.setPreferredSize(new Dimension(320, 40));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.BORDER, 1, true),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        field.setBackground(Palette.SURFACE);
        field.setForeground(Palette.TEXT);
        field.setCaretColor(Palette.ACCENT);
    }

    /**
     * Riga di un utente di prova. Un clic compila i due campi: in una
     * dimostrazione si cambia utente spesso, e i campi restano comunque vuoti
     * finché non lo si chiede, come in una schermata di accesso vera.
     */
    private JPanel demoUser(String username, String password, String description, Color color) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel left = new JLabel(username + " / " + password);
        left.setFont(Theme.mono(Font.PLAIN, 11));
        left.setForeground(color);
        left.setPreferredSize(new Dimension(150, 18));
        JLabel right = Ui.muted(description);
        right.setFont(Theme.regular(11));
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setToolTipText("Compila nome utente e password");
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                usernameField.setText(username);
                passwordField.setText(password);
                errorLabel.setText(" ");
                loginButton.requestFocusInWindow();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                right.setForeground(Palette.TEXT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                right.setForeground(Palette.TEXT_MUTED);
            }
        });
        return row;
    }

    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            errorLabel.setText("Inserisci nome utente e password.");
            return;
        }

        try {
            User user = model.authenticate(username, password);
            if (user == null) {
                errorLabel.setText("Credenziali non valide oppure utente disattivato.");
                passwordField.setText("");
                passwordField.requestFocusInWindow();
                return;
            }
            errorLabel.setText(" ");
            MainFrame main = new MainFrame(model);
            main.setVisible(true);
            dispose();
        } catch (DataAccessException e) {
            errorLabel.setText("Errore di accesso ai dati: " + e.getMessage());
        }
    }

    /**
     * Pannello decorativo di sinistra: sfondo sfumato e piantina stilizzata,
     * disegnati interamente in paintComponent. Nessuna immagine esterna, quindi
     * nessun file da distribuire insieme al programma.
     */
    private static class BrandPanel extends JPanel {

        private static final long serialVersionUID = 1L;

        BrandPanel() {
            setPreferredSize(new Dimension(430, 10));
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // Sfondo: sfumata diagonale fra due tonalita' vicine.
            g2.setPaint(new GradientPaint(0, 0, Palette.mix(Palette.BG, Palette.ACCENT, 0.07f),
                    w, h, Palette.mix(Palette.BG, Palette.TEAL, 0.05f)));
            g2.fillRect(0, 0, w, h);

            // Griglia leggera, come quella dell'editor di sala.
            g2.setColor(Palette.alpha(Palette.TEXT, 12));
            for (int x = 0; x < w; x += 28) {
                g2.drawLine(x, 0, x, h);
            }
            for (int y = 0; y < h; y += 28) {
                g2.drawLine(0, y, w, y);
            }

            // Piantina stilizzata: proporzioni relative, così segue la finestra.
            double s = Math.min(w / 430.0, h / 560.0);
            g2.translate(w * 0.5 - 150 * s, h * 0.5 - 110 * s);
            g2.scale(s, s);

            g2.setColor(Palette.alpha(Palette.TEXT, 26));
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.draw(new RoundRectangle2D.Double(0, 0, 300, 220, 12, 12));

            g2.setColor(Palette.alpha(Palette.ACCENT, 45));
            g2.fill(new RoundRectangle2D.Double(12, 12, 90, 60, 8, 8));
            g2.setColor(Palette.alpha(Palette.TEAL, 45));
            g2.fill(new RoundRectangle2D.Double(210, 12, 78, 44, 8, 8));

            Color tableFill = Palette.alpha(Palette.ACCENT, 150);
            Color tableEdge = Palette.alpha(Palette.ACCENT, 220);
            double[][] tables = {
                {30, 105, 46, 30}, {105, 105, 46, 30}, {180, 100, 40, 40}, {245, 100, 40, 40},
                {30, 165, 46, 30}, {105, 165, 46, 30}, {180, 160, 40, 40}
            };
            for (double[] t : tables) {
                boolean round = t[2] == t[3];
                java.awt.Shape shape = round
                        ? new Ellipse2D.Double(t[0], t[1], t[2], t[3])
                        : new RoundRectangle2D.Double(t[0], t[1], t[2], t[3], 6, 6);
                g2.setColor(tableFill);
                g2.fill(shape);
                g2.setColor(tableEdge);
                g2.draw(shape);
            }

            g2.setColor(Palette.alpha(Palette.TEXT, 60));
            g2.fill(new Ellipse2D.Double(140, 84, 10, 10));
            g2.fill(new Ellipse2D.Double(140, 196, 10, 10));

            g2.scale(1 / s, 1 / s);
            g2.translate(-(w * 0.5 - 150 * s), -(h * 0.5 - 110 * s));

            // Marchio e sottotitolo.
            g2.setColor(Palette.ACCENT);
            g2.setFont(Theme.bold(30));
            g2.drawString("RistoManager", 40, 76);

            g2.setColor(Palette.TEXT_MUTED);
            g2.setFont(Theme.regular(13));
            g2.drawString("Prenotazioni, sala e cucina in un unico posto.", 40, 100);

            g2.setColor(Palette.alpha(Palette.TEXT, 90));
            g2.setFont(Theme.regular(11));
            g2.drawString("Interfacce Grafiche e Programmazione ad Eventi", 40, h - 40);
            g2.dispose();
        }
    }
}
