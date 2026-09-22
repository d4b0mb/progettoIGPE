package it.unical.igpe.ristorante.view.common;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JPanel;

/**
 * Pannello con angoli arrotondati e bordo sottile: è il contenitore usato
 * per raggruppare i contenuti in tutta l'applicazione.
 *
 * Il disegno è fatto ridefinendo paintComponent, esattamente come nell'esempio
 * di View delle slide sull'MVC. Il pannello è dichiarato non opaco perché
 * altrimenti Swing riempirebbe di colore anche gli angoli, che devono restare
 * trasparenti per lasciar vedere lo sfondo sotto la curva.
 */
public class Card extends JPanel {

    private static final long serialVersionUID = 1L;

    private int arc = 14;
    private Color fill = Palette.SURFACE;
    private Color borderColor = Palette.BORDER;
    private boolean drawBorder = true;

    public Card() {
        this(null);
    }

    public Card(LayoutManager layout) {
        if (layout != null) {
            setLayout(layout);
        }
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        RoundRectangle2D shape = new RoundRectangle2D.Double(
                0.5, 0.5, getWidth() - 1.0, getHeight() - 1.0, arc, arc);

        g2.setColor(fill);
        g2.fill(shape);

        if (drawBorder) {
            g2.setColor(borderColor);
            g2.draw(shape);
        }
        g2.dispose();
        super.paintComponent(g);
    }

    public Card fill(Color color) {
        this.fill = color;
        return this;
    }

    public Card border(Color color) {
        this.borderColor = color;
        return this;
    }

    public Card noBorder() {
        this.drawBorder = false;
        return this;
    }

    public Card arc(int value) {
        this.arc = value;
        return this;
    }
}
