package it.unical.igpe.ristorante.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Barra di navigazione verticale a sinistra della finestra principale.
 *
 * È costruita con componenti disegnati a mano invece che con un JTabbedPane:
 * le schede standard non permettono ne' l'indicatore laterale ne' il badge con
 * il conteggio, e in un'applicazione usata tutta la sera la navigazione deve
 * essere leggibile in un colpo d'occhio.
 *
 * La selezione è gestita da un MouseListener, come nell'esempio di Controller
 * delle slide sull'MVC: il componente non decide nulla da solo, si limita a
 * riferire al chiamante quale voce è stata scelta.
 */
public class NavRail extends JPanel {

    private static final long serialVersionUID = 1L;

    private final List<NavItem> items = new ArrayList<>();
    private final Consumer<String> onSelect;
    private String selectedKey;
    private String pendingSection;

    public NavRail(Consumer<String> onSelect) {
        this.onSelect = onSelect;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(Palette.SURFACE);
        setPreferredSize(new Dimension(212, 10));
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Palette.BORDER));
    }

    /**
     * Registra un'intestazione di sezione, che però viene inserita solo quando
     * arriva la prima voce che le appartiene.
     *
     * Le sezioni dipendono dai permessi: un utente di sola lettura non ha
     * nessuna voce sotto "Cucina", e senza questo rinvio si vedrebbe un titolo
     * sospeso su un elenco vuoto.
     */
    public void addSection(String title) {
        this.pendingSection = title;
    }

    private void flushSection() {
        if (pendingSection == null) {
            return;
        }
        add(Ui.vGap(14));
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 18, 6, 0));
        wrapper.add(Ui.sectionTitle(pendingSection), BorderLayout.WEST);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        add(wrapper);
        pendingSection = null;
    }

    public void addItem(String key, String label, Color accent) {
        flushSection();
        NavItem item = new NavItem(key, label, accent);
        items.add(item);
        // La finestra principale associa Ctrl+1, Ctrl+2, ... alle voci
        // nell'ordine in cui compaiono: il suggerimento lo rende scopribile.
        item.setToolTipText(label + "   (Ctrl+" + items.size() + ")");
        add(item);
        add(Ui.vGap(2));
        if (selectedKey == null) {
            select(key);
        }
    }

    public void addFiller() {
        add(javax.swing.Box.createVerticalGlue());
    }

    public void addFooter(JComponent component) {
        component.setAlignmentX(LEFT_ALIGNMENT);
        add(component);
    }

    public void select(String key) {
        this.selectedKey = key;
        for (NavItem item : items) {
            item.repaint();
        }
        if (onSelect != null) {
            onSelect.accept(key);
        }
    }

    public String getSelectedKey() {
        return selectedKey;
    }

    /** Aggiorna il numero mostrato nel pallino a destra di una voce. */
    public void setBadge(String key, int count) {
        for (NavItem item : items) {
            if (item.key.equals(key)) {
                item.badgeCount = count;
                item.repaint();
            }
        }
    }

    public boolean hasItem(String key) {
        for (NavItem item : items) {
            if (item.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Chiavi delle voci presenti, nell'ordine in cui sono mostrate. */
    public List<String> getKeys() {
        List<String> keys = new ArrayList<>();
        for (NavItem item : items) {
            keys.add(item.key);
        }
        return keys;
    }

    /** Una singola voce della barra. */
    private class NavItem extends JComponent {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String label;
        private final Color accent;
        private boolean hover;
        private int badgeCount;

        NavItem(String key, String label, Color accent) {
            this.key = key;
            this.label = label;
            this.accent = accent;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setPreferredSize(new Dimension(212, 40));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    select(key);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            boolean selected = key.equals(selectedKey);
            int w = getWidth();
            int h = getHeight();

            if (selected) {
                g2.setColor(Palette.alpha(accent, 30));
                g2.fill(new RoundRectangle2D.Double(8, 3, w - 16, h - 6, 9, 9));
                g2.setColor(accent);
                g2.fill(new RoundRectangle2D.Double(0, 10, 4, h - 20, 4, 4));
            } else if (hover) {
                g2.setColor(Palette.alpha(Palette.TEXT, 14));
                g2.fill(new RoundRectangle2D.Double(8, 3, w - 16, h - 6, 9, 9));
            }

            g2.setFont(selected ? Theme.bold(13) : Theme.regular(13));
            g2.setColor(selected ? Palette.TEXT : Palette.TEXT_MUTED);
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.drawString(label, 22, h / 2 + fm.getAscent() / 2 - 2);

            if (badgeCount > 0) {
                String text = String.valueOf(badgeCount);
                g2.setFont(Theme.bold(10));
                java.awt.FontMetrics bfm = g2.getFontMetrics();
                int bw = Math.max(18, bfm.stringWidth(text) + 12);
                int bx = w - bw - 18;
                int by = h / 2 - 9;
                g2.setColor(accent);
                g2.fill(new RoundRectangle2D.Double(bx, by, bw, 18, 18, 18));
                g2.setColor(Palette.BG);
                g2.drawString(text, bx + (bw - bfm.stringWidth(text)) / 2, by + 13);
            }
            g2.dispose();
        }
    }
}
