package it.unical.igpe.ristorante.view.common;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;

/**
 * Etichetta "a pillola" colorata: stato di un tavolo, priorità di una comanda,
 * ruolo di un utente.
 *
 * Il testo è scritto in un colore pieno su uno sfondo dello stesso colore ma
 * molto trasparente: si ottiene un'etichetta leggibile che non urla, e che
 * funziona sia su tema chiaro sia su tema scuro senza doverla ricolorare.
 */
public class Badge extends JComponent {

    private static final long serialVersionUID = 1L;

    private String text;
    private Color color;
    private boolean solid;

    public Badge(String text, Color color) {
        this(text, color, false);
    }

    public Badge(String text, Color color, boolean solid) {
        this.text = text;
        this.color = color;
        this.solid = solid;
        setFont(Theme.bold(11));
    }

    public void setText(String text) {
        this.text = text;
        revalidate();
        repaint();
    }

    public void setColor(Color color) {
        this.color = color;
        repaint();
    }

    public String getText() {
        return text;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont() == null ? Theme.bold(11) : getFont());
        int width = fm.stringWidth(text == null ? "" : text) + 18;
        int height = fm.getHeight() + 8;
        return new Dimension(width, height);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int h = getHeight();
        RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, getWidth(), h, h, h);

        if (solid) {
            g2.setColor(color);
            g2.fill(shape);
            g2.setColor(Palette.BG);
        } else {
            g2.setColor(Palette.alpha(color, 38));
            g2.fill(shape);
            g2.setColor(Palette.alpha(color, 90));
            g2.draw(shape);
            g2.setColor(color);
        }

        Font font = getFont() == null ? Theme.bold(11) : getFont();
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        String label = text == null ? "" : text;
        int x = (getWidth() - fm.stringWidth(label)) / 2;
        int y = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(label, x, y);
        g2.dispose();
    }
}
