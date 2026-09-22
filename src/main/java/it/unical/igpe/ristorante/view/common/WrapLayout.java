package it.unical.igpe.ristorante.view.common;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * FlowLayout che va davvero a capo quando è dentro un JScrollPane.
 *
 * Il FlowLayout normale dichiara come dimensione preferita quella di UNA sola
 * riga contenente tutti i componenti. Dentro un pannello scorrevole questo
 * significa che i biglietti delle comande non vanno mai a capo: restano
 * incolonnati orizzontalmente e compare una barra di scorrimento laterale,
 * che è esattamente il contrario di quello che serve su un monitor di cucina.
 *
 * Questa sottoclasse calcola l'altezza preferita simulando la disposizione
 * sulla larghezza realmente disponibile, cosicché il contenitore cresca in
 * altezza e i biglietti si dispongano su più righe.
 */
public class WrapLayout extends FlowLayout {

    private static final long serialVersionUID = 1L;

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsets = insets.left + insets.right + hgap * 2;
            int maxWidth = targetWidth - horizontalInsets;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            for (int i = 0; i < target.getComponentCount(); i++) {
                java.awt.Component c = target.getComponent(i);
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();

                // La riga corrente è piena: si chiude e se ne apre una nuova.
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    dim.width = Math.max(dim.width, rowWidth);
                    dim.height += rowHeight + vgap;
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += hgap;
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }

            dim.width = Math.max(dim.width, rowWidth);
            dim.height += rowHeight;
            dim.width += horizontalInsets;
            dim.height += insets.top + insets.bottom + vgap * 2;

            // Dentro un viewport occorre restituire una larghezza che non superi
            // quella disponibile, altrimenti ricompare la barra orizzontale.
            java.awt.Container scrollPane =
                    SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                dim.width -= (hgap + 1);
            }
            return dim;
        }
    }
}
