package it.unical.igpe.ristorante.view.reservations;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.time.format.DateTimeFormatter;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.view.common.Palette;
import it.unical.igpe.ristorante.view.common.Theme;

/**
 * Disegna le celle della tabella prenotazioni.
 *
 * Un TableCellRenderer non è un componente per ogni cella: è UN SOLO
 * componente che viene riconfigurato e ridisegnato per ciascuna cella. Per
 * questo si evita di creare oggetti dentro getTableCellRendererComponent:
 * con qualche centinaio di righe verrebbero istanziati migliaia di oggetti
 * a ogni ridisegno.
 *
 * Il disegno è fatto a mano in paintComponent perché delle semplici stringhe
 * non basterebbero: servono pastiglie colorate per lo stato, il contrassegno
 * degli allergeni e le sigle dei tavoli.
 */
public class ReservationCellRenderer extends JComponent implements TableCellRenderer {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final RestaurantModel model;

    private Reservation reservation;
    private int column;
    private boolean selected;

    public ReservationCellRenderer(RestaurantModel model) {
        this.model = model;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                   boolean hasFocus, int row, int col) {
        this.reservation = (value instanceof Reservation r) ? r : null;
        this.column = table.convertColumnIndexToModel(col);
        this.selected = isSelected;
        // Il nome può essere accorciato per far posto al contrassegno FIDELITY:
        // passandoci sopra con il mouse lo si legge per intero.
        setToolTipText(column == ReservationTableModel.COL_GUEST && reservation != null
                ? reservation.getGuestName() : null);
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(80, 30);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(selected ? Palette.alpha(Palette.ACCENT, 34) : Palette.SURFACE);
        g2.fillRect(0, 0, w, h);

        if (reservation == null) {
            g2.dispose();
            return;
        }

        switch (column) {
            case ReservationTableModel.COL_TIME -> paintTime(g2, h);
            case ReservationTableModel.COL_GUEST -> paintGuest(g2, w, h);
            case ReservationTableModel.COL_PARTY -> paintParty(g2, h);
            case ReservationTableModel.COL_EXTRA -> paintExtra(g2, h);
            case ReservationTableModel.COL_TABLE -> paintTable(g2, h);
            case ReservationTableModel.COL_STATUS -> paintStatus(g2, h);
            case ReservationTableModel.COL_ALLERGENS -> paintAllergens(g2, w, h);
            case ReservationTableModel.COL_CONTACT -> paintContact(g2, h);
            default -> { /* nessun'altra colonna */ }
        }
        g2.dispose();
    }

    // ------------------------------------------------------------------

    private void paintTime(Graphics2D g2, int h) {
        g2.setFont(Theme.mono(java.awt.Font.BOLD, 13));
        g2.setColor(Palette.TEXT);
        String time = reservation.getDateTime() == null ? "--:--"
                : reservation.getDateTime().format(TIME);
        g2.drawString(time, 12, baseline(g2, h));
    }

    private void paintGuest(Graphics2D g2, int w, int h) {
        g2.setFont(Theme.bold(10));
        int pillSpace = reservation.isLoyaltyMember()
                ? g2.getFontMetrics().stringWidth("FIDELITY") + 14 + 8 : 0;

        // Un nome lungo si accorcia con "…" per lasciare posto al contrassegno
        // FIDELITY, invece di far tagliare il contrassegno dal bordo della cella.
        g2.setFont(Theme.bold(13));
        g2.setColor(Palette.TEXT);
        String name = fit(g2, reservation.getGuestName(), w - 24 - pillSpace);
        g2.drawString(name, 12, baseline(g2, h));

        if (reservation.isLoyaltyMember()) {
            int x = 12 + g2.getFontMetrics().stringWidth(name) + 8;
            pill(g2, x, h / 2 - 8, "FIDELITY", Palette.ACCENT);
        }
    }

    /** Accorcia il testo con "…" finché non entra nella larghezza indicata. */
    private static String fit(Graphics2D g2, String text, int width) {
        if (text == null) {
            return "";
        }
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

    private void paintParty(Graphics2D g2, int h) {
        g2.setFont(Theme.bold(13));
        g2.setColor(Palette.TEXT);
        g2.drawString(String.valueOf(reservation.getPartySize()), 14, baseline(g2, h));
        g2.setFont(Theme.regular(11));
        g2.setColor(Palette.TEXT_MUTED);
        g2.drawString("pax", 30, baseline(g2, h));
    }

    /** Seggioloni e passeggini: due contatori, disegnati solo se maggiori di zero. */
    private void paintExtra(Graphics2D g2, int h) {
        int x = 12;
        if (reservation.getHighChairs() > 0) {
            x = pill(g2, x, h / 2 - 9, reservation.getHighChairs() + " segg.", Palette.TEAL) + 6;
        }
        if (reservation.getStrollerSpaces() > 0) {
            pill(g2, x, h / 2 - 9, reservation.getStrollerSpaces() + " pass.", Palette.INFO);
        }
        if (reservation.getHighChairs() == 0 && reservation.getStrollerSpaces() == 0) {
            g2.setFont(Theme.regular(12));
            g2.setColor(Palette.alpha(Palette.TEXT_MUTED, 120));
            g2.drawString("—", 14, baseline(g2, h));
        }
    }

    private void paintTable(Graphics2D g2, int h) {
        RestaurantTable table = model.getFloorPlan().findTableById(reservation.getTableId());
        if (table == null) {
            // Id diverso da zero ma tavolo assente: è stato tolto dalla piantina
            // e la prenotazione va riassegnata. Lo si distingue dal "non ancora
            // assegnato", che invece è una situazione normale.
            boolean removed = reservation.getTableId() != 0;
            g2.setFont(Theme.regular(12));
            g2.setColor(removed ? Palette.DANGER : Palette.WARN);
            g2.drawString(removed ? "tavolo rimosso" : "da assegnare", 12, baseline(g2, h));
            return;
        }
        g2.setFont(Theme.bold(13));
        g2.setColor(Palette.TEXT);
        String label = "T" + table.getNumber();
        g2.drawString(label, 12, baseline(g2, h));

        // La x della seconda scritta si misura sulla PRIMA: usare la dimensione
        // del font al posto della larghezza del testo fa sovrapporre le due voci
        // appena il numero del tavolo passa da una a due cifre.
        int labelWidth = g2.getFontMetrics().stringWidth(label);
        g2.setFont(Theme.regular(11));
        g2.setColor(Palette.TEXT_MUTED);
        g2.drawString(table.getSeats() + " posti", 12 + labelWidth + 10, baseline(g2, h));
    }

    private void paintStatus(Graphics2D g2, int h) {
        Color color = switch (reservation.getStatus()) {
            case ATTESA -> Palette.WARN;
            case CONFERMATA -> Palette.OK;
            case ARRIVATA -> Palette.INFO;
            case COMPLETATA -> Palette.TEXT_MUTED;
            case ANNULLATA, NO_SHOW -> Palette.DANGER;
        };
        pill(g2, 12, h / 2 - 9, reservation.getStatus().getLabel(), color);
    }

    /**
     * Allergeni: sigla di tre lettere su fondo rosso.
     *
     * È l'informazione più critica dell'intera tabella, quindi non è un
     * testo qualunque ma un contrassegno che si vede prima di leggere la riga.
     */
    private void paintAllergens(Graphics2D g2, int w, int h) {
        if (!reservation.hasAllergens()) {
            g2.setFont(Theme.regular(12));
            g2.setColor(Palette.alpha(Palette.TEXT_MUTED, 120));
            g2.drawString("—", 14, baseline(g2, h));
            return;
        }
        int x = 10;
        for (Allergen allergen : reservation.getAllergens()) {
            if (x > w - 44) {
                g2.setFont(Theme.bold(10));
                g2.setColor(Palette.DANGER);
                g2.drawString("…", x + 2, baseline(g2, h));
                break;
            }
            x = pill(g2, x, h / 2 - 9, allergen.getShortCode(), Palette.DANGER) + 4;
        }
    }

    private void paintContact(Graphics2D g2, int h) {
        g2.setFont(Theme.regular(12));
        g2.setColor(Palette.TEXT_MUTED);
        String phone = reservation.getPhone() == null ? "—" : reservation.getPhone();
        g2.drawString(phone, 12, baseline(g2, h));
    }

    // ------------------------------------------------------------------

    /** Disegna una pastiglia colorata e restituisce la x del bordo destro. */
    private int pill(Graphics2D g2, int x, int y, String text, Color color) {
        g2.setFont(Theme.bold(10));
        FontMetrics fm = g2.getFontMetrics();
        int width = fm.stringWidth(text) + 14;
        int height = 18;

        RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, width, height, height, height);
        g2.setColor(Palette.alpha(color, 40));
        g2.fill(shape);
        g2.setColor(Palette.alpha(color, 110));
        g2.draw(shape);
        g2.setColor(color);
        g2.drawString(text, x + 7, y + height - 5);
        return x + width;
    }

    private int baseline(Graphics2D g2, int h) {
        FontMetrics fm = g2.getFontMetrics();
        return (h - fm.getHeight()) / 2 + fm.getAscent();
    }
}
