package it.unical.igpe.ristorante.view.kitchen;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import it.unical.igpe.ristorante.model.ModelEvent;
import it.unical.igpe.ristorante.model.ModelListener;
import it.unical.igpe.ristorante.model.Permission;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.model.kitchen.TicketStatus;
import it.unical.igpe.ristorante.net.KitchenClient;
import it.unical.igpe.ristorante.view.common.Card;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;
import it.unical.igpe.ristorante.view.common.Ui;

/**
 * Monitor della cucina: la coda delle comande in arrivo dalla sala.
 *
 * Le comande non vengono chieste al server a intervalli regolari: è il server
 * a spingerle sul socket appena arrivano. Questo pannello si limita a reagire
 * all'evento del Model, che a sua volta è stato aggiornato dal thread di
 * lettura del client attraverso SwingUtilities.invokeLater.
 *
 * L'unico timer presente aggiorna i cronometri: quello è un dato che cambia
 * da solo con il passare del tempo, non per un messaggio ricevuto.
 */
public class KitchenPanel extends JPanel implements ModelListener {

    private static final long serialVersionUID = 1L;

    private final RestaurantModel model;
    private final KitchenClient client;
    private final boolean canManage;

    private final JPanel board = new JPanel();
    private final List<TicketCard> cards = new ArrayList<>();

    private final JLabel countNew = new JLabel("0");
    private final JLabel countProgress = new JLabel("0");
    private final JLabel countReady = new JLabel("0");
    private final JLabel countLate = new JLabel("0");

    private final JCheckBox showClosed = new JCheckBox("Mostra anche le comande chiuse");

    /** Aggiorna i cronometri una volta al secondo; si ferma con detach(). */
    private final Timer clock;

    public KitchenPanel(RestaurantModel model, KitchenClient client) {
        this.model = model;
        this.client = client;
        this.canManage = model.can(Permission.MANAGE_KITCHEN);

        setLayout(new BorderLayout(0, 14));
        setBackground(Palette.BG);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBoard(), BorderLayout.CENTER);

        model.addListener(this);
        rebuild();

        // Un colpo al secondo: aggiorna i cronometri senza ricostruire i
        // biglietti, che verrebbero ridisegnati inutilmente sessanta volte al minuto.
        clock = new Timer(1000, e -> {
            for (TicketCard card : cards) {
                card.updateElapsed();
            }
            updateCounters();
        });
        clock.start();
    }

    /** Da chiamare quando la finestra si chiude: ferma il cronometro e l'iscrizione al Model. */
    public void detach() {
        clock.stop();
        model.removeListener(this);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(Ui.h1("Monitor cucina"));
        if (!canManage) {
            left.add(new it.unical.igpe.ristorante.view.common.Badge(
                    "SOLA CONSULTAZIONE", Palette.TEXT_MUTED));
        }
        top.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        showClosed.setOpaque(false);
        showClosed.setForeground(Palette.TEXT_MUTED);
        showClosed.setFont(Theme.regular(12));
        showClosed.addActionListener(e -> rebuild());
        right.add(showClosed);
        right.add(new it.unical.igpe.ristorante.view.common.ClockLabel(false));
        top.add(right, BorderLayout.EAST);
        header.add(top, BorderLayout.NORTH);

        JPanel counters = new JPanel(new GridLayout(1, 4, 12, 0));
        counters.setOpaque(false);
        counters.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        counters.setPreferredSize(new Dimension(10, 84));
        counters.add(counter("Nuove", countNew, Palette.INFO));
        counters.add(counter("In preparazione", countProgress, Palette.WARN));
        counters.add(counter("Pronte da servire", countReady, Palette.OK));
        counters.add(counter("In ritardo", countLate, Palette.DANGER));
        header.add(counters, BorderLayout.CENTER);
        return header;
    }

    private JComponent counter(String caption, JLabel value, Color color) {
        Card card = new Card(new BorderLayout(0, 2));
        card.fill(Palette.SURFACE).border(Palette.BORDER);
        card.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        value.setFont(Theme.bold(26));
        value.setForeground(color);
        card.add(Ui.sectionTitle(caption), BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildBoard() {
        // WrapLayout invece di FlowLayout: i biglietti devono andare a capo,
        // non allinearsi tutti su una riga con la barra di scorrimento orizzontale.
        board.setLayout(new it.unical.igpe.ristorante.view.common.WrapLayout(
                java.awt.FlowLayout.LEFT, 14, 14));
        board.setBackground(Palette.BG);

        JScrollPane scroll = new JScrollPane(board);
        scroll.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
        scroll.getViewport().setBackground(Palette.BG);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    /**
     * Ricostruisce la bacheca.
     *
     * Le comande sono ordinate secondo il compareTo di Ticket: prima la
     * priorità più alta, poi la più vecchia. È la stessa regola con cui
     * lavora una cucina vera.
     */
    private void rebuild() {
        board.removeAll();
        cards.clear();

        List<Ticket> tickets = new ArrayList<>(model.getTickets());
        if (!showClosed.isSelected()) {
            tickets.removeIf(t -> !t.getStatus().isOpen() && t.getStatus() != TicketStatus.PRONTA);
        }
        Collections.sort(tickets);

        if (tickets.isEmpty()) {
            JLabel empty = new JLabel(
                    "<html><div style='text-align:center'>Nessuna comanda in coda.<br>"
                    + "Le nuove comande inviate dalla sala compaiono qui automaticamente.</div></html>",
                    SwingConstants.CENTER);
            empty.setFont(Theme.regular(13));
            empty.setForeground(Palette.TEXT_MUTED);
            empty.setPreferredSize(new Dimension(600, 120));
            board.add(empty);
        } else {
            for (Ticket ticket : tickets) {
                TicketCard card = new TicketCard(ticket,
                        canManage ? TicketCard.Mode.CUCINA : TicketCard.Mode.LETTURA,
                        newStatus -> changeStatus(ticket, newStatus));
                cards.add(card);
                board.add(card);
            }
        }

        board.revalidate();
        board.repaint();
        updateCounters();
    }

    /**
     * Cambio di stato deciso in cucina.
     *
     * Il pannello NON modifica direttamente il proprio Model: manda
     * l'aggiornamento al server, che lo salva e lo ritrasmette a tutte le
     * postazioni. Così anche la sala vede la comanda diventare "pronta", e
     * non esiste il caso in cui due schermi mostrino stati diversi.
     */
    private void changeStatus(Ticket ticket, TicketStatus newStatus) {
        ticket.advanceTo(newStatus);
        if (!client.sendTicketUpdate(ticket)) {
            // Senza connessione l'aggiornamento resta su questa postazione:
            // meglio che perderlo. Il server non lo conosce finché non torna.
            model.upsertTicket(ticket);
        }
        rebuild();
    }

    private void updateCounters() {
        int nuove = 0;
        int inCorso = 0;
        int pronte = 0;
        int ritardo = 0;
        for (Ticket t : model.getTickets()) {
            switch (t.getStatus()) {
                case NUOVA -> nuove++;
                case IN_PREPARAZIONE -> inCorso++;
                case PRONTA -> pronte++;
                default -> { /* servite e annullate non entrano nei contatori */ }
            }
            if (t.isLate()) {
                ritardo++;
            }
        }
        countNew.setText(String.valueOf(nuove));
        countProgress.setText(String.valueOf(inCorso));
        countReady.setText(String.valueOf(pronte));
        countLate.setText(String.valueOf(ritardo));
    }

    @Override
    public void onModelChanged(ModelEvent event) {
        if (event.is(ModelEvent.Type.TICKETS_CHANGED)) {
            rebuild();
        }
    }
}
