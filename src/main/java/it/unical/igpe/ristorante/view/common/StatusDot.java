package it.unical.igpe.ristorante.view.common;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;

/** Pallino colorato, con un alone più chiaro attorno. Indica uno stato. */
public class StatusDot extends JComponent {

    private static final long serialVersionUID = 1L;

    private Color color;
    private int diameter;

    public StatusDot(Color color) {
        this(color, 10);
    }

    public StatusDot(Color color, int diameter) {
        this.color = color;
        this.diameter = diameter;
        setPreferredSize(new Dimension(diameter + 8, diameter + 8));
    }

    public void setColor(Color color) {
        this.color = color;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        g2.setColor(Palette.alpha(color, 55));
        g2.fillOval(cx - diameter / 2 - 3, cy - diameter / 2 - 3, diameter + 6, diameter + 6);
        g2.setColor(color);
        g2.fillOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter);
        g2.dispose();
    }
}
