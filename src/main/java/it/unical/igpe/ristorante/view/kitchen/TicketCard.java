package it.unical.igpe.ristorante.view.kitchen;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.model.kitchen.TicketItem;
import it.unical.igpe.ristorante.model.kitchen.TicketPriority;
import it.unical.igpe.ristorante.model.kitchen.TicketStatus;
import it.unical.igpe.ristorante.view.common.Badge;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Il "biglietto" di una comanda, come appare sul monitor della cucina.
 *
 * Regole di lettura che il disegno rispetta, nell'ordine:
 *   1. la fascia colorata in alto dice la PRIORITA' e si vede da lontano;
 *   2. la banda rossa delle ALLERGIE DEL CLIENTE, se ce ne sono, viene prima
 *      dei piatti, e i piatti che le contengono sono scritti in rosso;
 *   3. il tempo trascorso diventa rosso quando la comanda è in ritardo.
 *
 * Sono tutte informazioni che in cucina si leggono di sfuggita, in movimento:
 * per questo sono affidate al colore e alla posizione, non al testo. La banda
 * rossa compare solo quando c'è davvero un'allergia dichiarata: se comparisse
 * per ogni piatto che contiene glutine o latte (quasi tutti) nessuno ci
 * farebbe più caso.
 */
public class TicketCard extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Larghezza utile del biglietto, al netto dei margini. */
    private static final int CONTENT_WIDTH = 272;

    /**
     * Da quale postazione viene guardato il biglietto.
     *
     * Cambia solo quali passaggi di stato sono offerti: la cucina prende in
     * carico e dichiara pronto, la sala segna servito quando porta il piatto
     * al tavolo. Lo stesso componente serve entrambe senza duplicazioni.
     */
    public enum Mode { CUCINA, SALA, LETTURA }

    private final transient Ticket ticket;
    private final transient Consumer<TicketStatus> onStatusChange;
    private final Mode mode;

    private final JLabel elapsedLabel = new JLabel();
    private final JLabel allergyLabel = new JLabel();

    public TicketCard(Ticket ticket, Mode mode, Consumer<TicketStatus> onStatusChange) {
        this.ticket = ticket;
        this.mode = mode;
        this.onStatusChange = onStatusChange;

        // L'etichetta delle allergie si prepara subito: la sua altezza reale
        // serve già a computeHeight() per dimensionare il biglietto.
        if (ticket.hasGuestAllergens()) {
            allergyLabel.setFont(Theme.bold(10));
            allergyLabel.setForeground(Palette.TEXT);
            allergyLabel.setText(Ui.wrapped(bannerText(), CONTENT_WIDTH - 20));
        }

        setOpaque(false);
        setLayout(new BorderLayout());
        int height = computeHeight();
        setPreferredSize(new Dimension(300, height));
        setMaximumSize(new Dimension(300, height));
        setBorder(BorderFactory.createEmptyBorder(14, 14, 12, 14));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildItems(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    private int computeHeight() {
        int base = 132;
        base += ticket.getItems().size() * 21;
        if (ticket.hasGuestAllergens()) {
            // La banda cresce con il numero di righe che il testo occupa:
            // con un'altezza fissa le allergie oltre la prima riga sparirebbero,
            // ed è l'ultima informazione che ci si può permettere di perdere.
            base += allergenBannerHeight() + 8;
        }
        if (ticket.hasAllergens()) {
            base += 18;
        }
        if (!ticket.getNote().isBlank()) {
            base += 24;
        }
        return Math.min(560, base);
    }

    /** Testo della banda: le allergie del cliente e quanti piatti le contengono. */
    private String bannerText() {
        String red = Palette.hex(Palette.DANGER);
        StringBuilder sb = new StringBuilder("<b style='color:").append(red)
                .append("'>ALLERGIE DEL CLIENTE:</b> ").append(labels(ticket.getGuestAllergens()));
        int conflicts = 0;
        for (TicketItem item : ticket.getItems()) {
            if (!ticket.conflictsOf(item).isEmpty()) {
                conflicts++;
            }
        }
        if (conflicts > 0) {
            sb.append("<br><b style='color:").append(red).append("'>ATTENZIONE:</b> ")
              .append(conflicts).append(conflicts == 1 ? " piatto le contiene" : " piatti le contengono");
        }
        return sb.toString();
    }

    /**
     * Altezza effettiva della banda, chiesta direttamente all'etichetta.
     *
     * Meglio misurare che stimare: una stima basata sul numero di caratteri
     * sbaglia appena cambia il font o si aggiunge un allergene dal nome lungo,
     * e l'errore si vede subito come testo tagliato.
     */
    private int allergenBannerHeight() {
        return allergyLabel.getPreferredSize().height + 10;
    }

    /**
     * Sfondo del biglietto: superficie, bordo colorato per priorità e fascia
     * superiore piena. In ritardo il bordo diventa rosso, indipendentemente
     * dalla priorità.
     */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color priorityColor = Palette.forPriority(ticket.getPriority());
        Color edge = ticket.isLate() ? Palette.DANGER : priorityColor;

        RoundRectangle2D shape = new RoundRectangle2D.Double(
                0.5, 0.5, getWidth() - 1.0, getHeight() - 1.0, 12, 12);

        g2.setColor(Palette.SURFACE);
        g2.fill(shape);

        // Fascia di priorità in alto, ritagliata dentro l'arrotondamento.
        Shape oldClip = g2.getClip();
        g2.clip(shape);
        g2.setColor(priorityColor);
        g2.fillRect(0, 0, getWidth(), 5);
        g2.setClip(oldClip);

        g2.setColor(Palette.alpha(edge, ticket.isLate() ? 220 : 120));
        g2.setStroke(new BasicStroke(ticket.isLate() ? 2f : 1f));
        g2.draw(shape);
        g2.dispose();
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));

        JPanel left = Ui.column();
        JLabel table = new JLabel("Tavolo " + ticket.getTableNumber());
        table.setFont(Theme.bold(17));
        table.setForeground(Palette.TEXT);
        left.add(Ui.alignLeft(table));
        left.add(Ui.vGap(2));

        String subtitle = ticket.getCode()
                + (ticket.getGuestName().isBlank() ? "" : "  ·  " + ticket.getGuestName());
        JLabel code = Ui.muted(subtitle);
        code.setFont(Theme.regular(11));
        left.add(Ui.alignLeft(code));
        header.add(left, BorderLayout.WEST);

        JPanel right = Ui.column();
        elapsedLabel.setFont(Theme.mono(Font.BOLD, 14));
        elapsedLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        elapsedLabel.setAlignmentX(RIGHT_ALIGNMENT);
        updateElapsed();

        JLabel sentAt = Ui.muted("inviata " + ticket.getCreatedAt().format(TIME));
        sentAt.setFont(Theme.regular(10));
        sentAt.setAlignmentX(RIGHT_ALIGNMENT);

        right.add(elapsedLabel);
        right.add(Ui.vGap(2));
        right.add(sentAt);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildItems() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);

        if (ticket.hasGuestAllergens()) {
            body.add(Ui.alignLeft(buildAllergenBanner()));
            body.add(Ui.vGap(8));
        }

        // In ordine di portata: è l'ordine in cui la cucina prepara i piatti.
        for (TicketItem item : ticket.getItemsInCourseOrder()) {
            body.add(Ui.alignLeft(buildItemRow(item)));
        }

        if (ticket.hasAllergens()) {
            body.add(Ui.vGap(4));
            JLabel contains = Ui.muted("Nei piatti: " + codes(ticket.getAllAllergens()));
            contains.setFont(Theme.regular(10));
            body.add(Ui.alignLeft(contains));
        }

        if (!ticket.getNote().isBlank()) {
            body.add(Ui.vGap(6));
            JLabel note = new JLabel("Nota: " + ticket.getNote());
            note.setFont(Theme.regular(11));
            note.setForeground(Palette.WARN);
            body.add(Ui.alignLeft(note));
        }
        return body;
    }

    /** Banda rossa con le allergie dichiarate dal cliente. */
    private JComponent buildAllergenBanner() {
        JPanel banner = new JPanel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Palette.alpha(Palette.DANGER, 45));
                g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 8, 8));
                g2.setColor(Palette.alpha(Palette.DANGER, 150));
                g2.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1.0, getHeight() - 1.0, 8, 8));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        banner.setOpaque(false);
        int height = allergenBannerHeight();
        banner.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
        banner.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
        banner.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        banner.add(allergyLabel, BorderLayout.CENTER);
        return banner;
    }

    /**
     * Una riga della comanda. Se il piatto contiene un allergene dichiarato
     * dal cliente, la riga è rossa e al posto della portata mostra le sigle
     * degli allergeni in conflitto.
     */
    private JComponent buildItemRow(TicketItem item) {
        Set<Allergen> conflicts = ticket.conflictsOf(item);
        boolean danger = !conflicts.isEmpty();

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(280, 20));

        JLabel quantity = new JLabel(item.getQuantity() + "×");
        quantity.setFont(Theme.mono(Font.BOLD, 12));
        quantity.setForeground(danger ? Palette.DANGER : Palette.ACCENT);
        quantity.setPreferredSize(new Dimension(26, 18));

        String text = item.getName();
        if (!item.getNote().isBlank()) {
            text += " (" + item.getNote() + ")";
        }
        JLabel name = new JLabel(text);
        name.setFont(danger ? Theme.bold(12) : Theme.regular(12));
        name.setForeground(danger ? Palette.DANGER : Palette.TEXT);

        JLabel right;
        if (danger) {
            right = new JLabel(codes(conflicts));
            right.setFont(Theme.bold(10));
            right.setForeground(Palette.DANGER);
            name.setToolTipText("Contiene " + labels(conflicts).toLowerCase()
                    + ": il cliente ha dichiarato di non poterlo mangiare");
        } else {
            right = new JLabel(item.getCourse().getLabel().substring(0, 3).toUpperCase());
            right.setFont(Theme.regular(9));
            right.setForeground(Palette.TEXT_MUTED);
        }

        row.add(quantity, BorderLayout.WEST);
        row.add(name, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(6, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        left.add(new Badge(ticket.getStatus().getLabel().toUpperCase(),
                Palette.forTicketStatus(ticket.getStatus())));
        if (ticket.getPriority() != TicketPriority.NORMALE) {
            // Nel piede si usa il nome breve della priorità: l'etichetta estesa
            // ruberebbe lo spazio al pulsante di avanzamento.
            left.add(new Badge(ticket.getPriority().name(),
                    Palette.forPriority(ticket.getPriority()), true));
        }
        footer.add(left, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        if (mode == Mode.CUCINA) {
            switch (ticket.getStatus()) {
                case NUOVA -> actions.add(actionButton("In carico",
                        TicketStatus.IN_PREPARAZIONE, Palette.WARN));
                case IN_PREPARAZIONE -> actions.add(actionButton("Pronta",
                        TicketStatus.PRONTA, Palette.OK));
                case PRONTA -> actions.add(actionButton("Servita",
                        TicketStatus.SERVITA, Palette.TEXT_MUTED));
                default -> { /* comanda chiusa: nessuna azione */ }
            }
        } else if (mode == Mode.SALA && ticket.getStatus() == TicketStatus.PRONTA) {
            actions.add(actionButton("Servita", TicketStatus.SERVITA, Palette.OK));
        }
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private JButton actionButton(String text, TicketStatus target, Color color) {
        JButton button = Ui.toolButton(text);
        button.setForeground(color);
        button.setFont(Theme.bold(12));
        button.addActionListener(e -> onStatusChange.accept(target));
        return button;
    }

    private static String labels(Set<Allergen> allergens) {
        StringBuilder sb = new StringBuilder();
        for (Allergen a : allergens) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(a.getLabel().toUpperCase());
        }
        return sb.toString();
    }

    private static String codes(Set<Allergen> allergens) {
        StringBuilder sb = new StringBuilder();
        for (Allergen a : allergens) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a.getShortCode());
        }
        return sb.toString();
    }

    /** Ricalcola il tempo trascorso; richiamato una volta al secondo dal pannello. */
    public void updateElapsed() {
        elapsedLabel.setText(ticket.getElapsedText());
        elapsedLabel.setForeground(ticket.isLate() ? Palette.DANGER
                : (ticket.getStatus().isOpen() ? Palette.TEXT : Palette.TEXT_MUTED));
        repaint();
    }

    public Ticket getTicket() {
        return ticket;
    }
}
