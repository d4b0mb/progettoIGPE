package it.unical.igpe.ristorante.view.common;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Notifica temporanea che compare in alto a destra e sparisce da sola.
 *
 * Serve per gli eventi che arrivano mentre l'utente sta facendo altro: una
 * comanda nuova in cucina, un piatto pronto in sala, un salvataggio riuscito.
 * Una finestra di dialogo bloccherebbe il lavoro; una scritta nella barra di
 * stato non la noterebbe nessuno.
 *
 * Il componente viene aggiunto al JLayeredPane della finestra, nello strato
 * POPUP_LAYER: sta sopra a tutto il contenuto senza essere una finestra a sé.
 * La comparsa e la dissolvenza sono fatte con un javax.swing.Timer che cambia
 * l'opacità a ogni colpo: il Timer esegue sull'EDT, quindi può ridisegnare
 * senza bisogno di invokeLater.
 */
public final class Toast extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int WIDTH = 350;
    private static final int HEIGHT = 62;
    private static final int MARGIN = 18;
    private static final int TOP = 70;
    private static final int FADE_IN_MILLIS = 180;
    private static final int VISIBLE_MILLIS = 4500;
    private static final int FADE_OUT_MILLIS = 450;
    private static final int FRAME_MILLIS = 30;

    private final String title;
    private final String message;
    private final Color accent;
    private final Timer timer;

    private float opacity;
    private int elapsed;

    private Toast(String title, String message, Color accent) {
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.accent = accent;
        this.timer = new Timer(FRAME_MILLIS, e -> tick());
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Clic per chiudere");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dismiss();
            }
        });
    }

    /**
     * Mostra una notifica nella finestra che contiene {@code anchor}.
     * Le notifiche successive si impilano sotto quelle ancora visibili.
     */
    public static void show(Component anchor, String title, String message, Color accent) {
        JRootPane root = SwingUtilities.getRootPane(anchor);
        if (root == null) {
            return;
        }
        JLayeredPane layers = root.getLayeredPane();
        int y = TOP;
        for (Component c : layers.getComponentsInLayer(JLayeredPane.POPUP_LAYER)) {
            if (c instanceof Toast) {
                y = Math.max(y, c.getY() + c.getHeight() + 10);
            }
        }
        Toast toast = new Toast(title, message, accent);
        toast.setBounds(Math.max(MARGIN, layers.getWidth() - WIDTH - MARGIN), y, WIDTH, HEIGHT);
        layers.add(toast, JLayeredPane.POPUP_LAYER);
        toast.timer.start();
    }

    private void tick() {
        elapsed += FRAME_MILLIS;
        if (elapsed < FADE_IN_MILLIS) {
            opacity = elapsed / (float) FADE_IN_MILLIS;
        } else if (elapsed < VISIBLE_MILLIS) {
            opacity = 1f;
        } else if (elapsed < VISIBLE_MILLIS + FADE_OUT_MILLIS) {
            opacity = 1f - (elapsed - VISIBLE_MILLIS) / (float) FADE_OUT_MILLIS;
        } else {
            dismiss();
            return;
        }
        repaint();
    }

    private void dismiss() {
        timer.stop();
        Container parent = getParent();
        if (parent != null) {
            parent.remove(this);
            parent.repaint(getX(), getY(), getWidth(), getHeight());
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                Math.max(0f, Math.min(1f, opacity))));

        RoundRectangle2D shape = new RoundRectangle2D.Double(
                0.5, 0.5, getWidth() - 1.0, getHeight() - 1.0, 12, 12);
        g2.setColor(Palette.SURFACE_2);
        g2.fill(shape);

        // Striscia colorata a sinistra, ritagliata dentro l'arrotondamento.
        Shape oldClip = g2.getClip();
        g2.clip(shape);
        g2.setColor(accent);
        g2.fillRect(0, 0, 5, getHeight());
        g2.setClip(oldClip);

        g2.setColor(Palette.alpha(accent, 150));
        g2.draw(shape);

        int textWidth = getWidth() - 36;
        g2.setFont(Theme.bold(13));
        g2.setColor(Palette.TEXT);
        g2.drawString(fit(g2, title, textWidth), 20, 26);
        g2.setFont(Theme.regular(12));
        g2.setColor(Palette.TEXT_MUTED);
        g2.drawString(fit(g2, message, textWidth), 20, 45);
        g2.dispose();
    }

    /** Accorcia il testo con "…" se non entra nella larghezza disponibile. */
    private static String fit(Graphics2D g2, String text, int width) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end) + "…") > width) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
