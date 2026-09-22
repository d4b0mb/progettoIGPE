package it.unical.igpe.ristorante.view.reservations;

import java.util.ArrayList;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import it.unical.igpe.ristorante.model.Reservation;

/**
 * Modello dati della tabella delle prenotazioni.
 *
 * Estende AbstractTableModel invece di usare DefaultTableModel: quest'ultimo
 * costringerebbe a copiare ogni prenotazione in un Vector di stringhe, cioe' a
 * mantenere due copie degli stessi dati che possono divergere. Così invece la
 * tabella legge direttamente la lista di oggetti Reservation.
 *
 * getValueAt restituisce sempre l'intera prenotazione: il renderer decide poi
 * cosa disegnare in base alla colonna. È quello che permette di mostrare in
 * una cella un'etichetta colorata invece che del semplice testo.
 */
public class ReservationTableModel extends AbstractTableModel {

    private static final long serialVersionUID = 1L;

    public static final int COL_TIME = 0;
    public static final int COL_GUEST = 1;
    public static final int COL_PARTY = 2;
    public static final int COL_EXTRA = 3;
    public static final int COL_TABLE = 4;
    public static final int COL_STATUS = 5;
    public static final int COL_ALLERGENS = 6;
    public static final int COL_CONTACT = 7;

    private static final String[] COLUMNS = {
            "Ora", "Cliente", "Coperti", "Seggioloni / Passeggini",
            "Tavolo", "Stato", "Allergeni", "Contatto"
    };

    private final List<Reservation> rows = new ArrayList<>();

    public void setRows(List<Reservation> reservations) {
        rows.clear();
        if (reservations != null) {
            rows.addAll(reservations);
        }
        // Avvisa la JTable che TUTTO il contenuto è cambiato e va ridisegnato.
        fireTableDataChanged();
    }

    public Reservation getReservationAt(int modelRow) {
        if (modelRow < 0 || modelRow >= rows.size()) {
            return null;
        }
        return rows.get(modelRow);
    }

    public int indexOf(Reservation reservation) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getId() == reservation.getId()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return Reservation.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        // La modifica avviene sempre dalla finestra di dettaglio, mai
        // direttamente in cella: così le validazioni non si possono aggirare.
        return false;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        return rows.get(rowIndex);
    }
}
