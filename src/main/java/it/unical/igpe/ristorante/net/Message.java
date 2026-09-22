package it.unical.igpe.ristorante.net;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import it.unical.igpe.ristorante.model.kitchen.Ticket;

/**
 * Unità di comunicazione fra client e server.
 *
 * Viaggia sul socket attraverso ObjectOutputStream / ObjectInputStream, quindi
 * DEVE implementare Serializable, e così deve fare tutto ciò che contiene
 * (Ticket, TicketItem, gli enum). Un solo campo non serializzabile e l'invio
 * fallirebbe a runtime con una NotSerializableException.
 *
 * serialVersionUID è dichiarato esplicitamente: senza, il valore viene
 * calcolato dalla struttura della classe e basterebbe aggiungere un campo per
 * rendere incompatibili due versioni del programma.
 */
public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    private final MessageType type;
    private String senderName = "";
    private String station = "";
    private String text = "";
    private Ticket ticket;
    private List<Ticket> tickets = new ArrayList<>();
    private final LocalDateTime timestamp = LocalDateTime.now();

    public Message(MessageType type) {
        this.type = type;
    }

    public static Message hello(String senderName, String station) {
        Message m = new Message(MessageType.HELLO);
        m.senderName = senderName;
        m.station = station;
        return m;
    }

    public static Message newTicket(Ticket ticket, String senderName) {
        Message m = new Message(MessageType.TICKET_NEW);
        m.ticket = ticket;
        m.senderName = senderName;
        return m;
    }

    public static Message updateTicket(Ticket ticket, String senderName) {
        Message m = new Message(MessageType.TICKET_UPDATE);
        m.ticket = ticket;
        m.senderName = senderName;
        return m;
    }

    public static Message snapshot(List<Ticket> tickets) {
        Message m = new Message(MessageType.SNAPSHOT);
        m.tickets = new ArrayList<>(tickets);
        return m;
    }

    public static Message chat(String senderName, String text) {
        Message m = new Message(MessageType.CHAT);
        m.senderName = senderName;
        m.text = text;
        return m;
    }

    public static Message error(String text) {
        Message m = new Message(MessageType.ERROR);
        m.text = text;
        return m;
    }

    public MessageType getType() { return type; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getStation() { return station; }
    public void setStation(String station) { this.station = station; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Ticket getTicket() { return ticket; }
    public void setTicket(Ticket ticket) { this.ticket = ticket; }

    public List<Ticket> getTickets() { return tickets; }

    public LocalDateTime getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return type + (ticket != null ? " [" + ticket.getCode() + "]" : "");
    }
}
