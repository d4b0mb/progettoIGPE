package it.unical.igpe.ristorante.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import it.unical.igpe.ristorante.model.floor.FloorPlan;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.model.floor.TableStatus;
import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.persistence.Database;
import it.unical.igpe.ristorante.persistence.FloorPlanDao;
import it.unical.igpe.ristorante.persistence.PasswordHasher;
import it.unical.igpe.ristorante.persistence.ReservationDao;
import it.unical.igpe.ristorante.persistence.UserDao;

/**
 * Il Model dell'applicazione, nel senso preciso che ha nelle slide sull'MVC:
 * dati di dominio, logica applicativa e meccanismo di persistenza.
 *
 * Regole che valgono per tutto il progetto:
 *  - qui dentro non compare NESSUNA classe di Swing. Il Model non sa che esiste
 *    un'interfaccia grafica; se lo sapesse, non sarebbe riutilizzabile ne'
 *    testabile senza aprire una finestra. I test in src/test/java lo usano
 *    proprio così, senza interfaccia.
 *  - ogni modifica dei dati termina con una notifica ai listener registrati.
 *    Le View si aggiornano di conseguenza, senza che il Model le conosca.
 */
public class RestaurantModel {

    /** Controllo sintattico dell'email: volutamente permissivo, non un validatore RFC. */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");

    private final Database database;
    private final UserDao userDao;
    private final ReservationDao reservationDao;
    private final FloorPlanDao floorPlanDao;

    private final List<ModelListener> listeners = new ArrayList<>();

    private final List<User> users = new ArrayList<>();
    private final List<Reservation> reservations = new ArrayList<>();
    private final List<Ticket> tickets = new ArrayList<>();

    /**
     * La piantina è sempre lo STESSO oggetto per tutta la vita del Model:
     * ricaricarla o annullare una modifica ne sostituisce il contenuto
     * (FloorPlan.restoreFrom), non il riferimento. Così nessuna vista può
     * restare a disegnare una copia ormai vecchia.
     */
    private final FloorPlan floorPlan = new FloorPlan();

    /** Utente attualmente autenticato; null finché non si effettua il login. */
    private User currentUser;

    /** Versione dei dati letta per ultima: vedi syncWithDatabase(). */
    private long knownDataVersion;

    public RestaurantModel(Database database) {
        this.database = database;
        this.userDao = new UserDao(database);
        this.reservationDao = new ReservationDao(database);
        this.floorPlanDao = new FloorPlanDao(database);
        reloadAll();
    }

    // ------------------------------------------------------------------
    // Observer
    // ------------------------------------------------------------------

    public void addListener(ModelListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(ModelListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifica tutti gli osservatori.
     *
     * Si itera su una copia della lista: un listener potrebbe decidere di
     * disiscriversi mentre viene notificato, e modificare la lista durante un
     * ciclo su di essa provoca una ConcurrentModificationException.
     */
    public void fireEvent(ModelEvent.Type type, Object source) {
        ModelEvent event = new ModelEvent(type, source);
        for (ModelListener listener : new ArrayList<>(listeners)) {
            listener.onModelChanged(event);
        }
    }

    // ------------------------------------------------------------------
    // Caricamento e allineamento con le altre postazioni
    // ------------------------------------------------------------------

    public final void reloadAll() {
        reloadUsers();
        reloadReservations();
        FloorPlan loaded = floorPlanDao.loadFirst();
        if (loaded != null) {
            floorPlan.restoreFrom(loaded);
        }
        refreshTableStatuses(LocalDateTime.now());
        knownDataVersion = database.getDataVersion();
    }

    private void reloadUsers() {
        users.clear();
        users.addAll(userDao.findAll());
    }

    private void reloadReservations() {
        reservations.clear();
        reservations.addAll(reservationDao.findAll());
    }

    /**
     * Allinea questa postazione con le modifiche fatte dalle altre postazioni
     * che usano lo stesso file di database, cioè quelle sullo stesso computer
     * (fra computer diversi viaggiano solo le comande, attraverso il server).
     *
     * Ogni postazione ha il proprio Model in memoria, caricato all'avvio:
     * senza questo controllo una prenotazione inserita in sala comparirebbe
     * sull'altra postazione solo dopo un riavvio. Il database dice se qualcun
     * altro ha scritto (Database.getDataVersion); solo in quel caso si
     * rileggono prenotazioni e utenti e si avvisano le viste.
     *
     * La piantina invece NON viene ricaricata: potrebbe essere aperta in
     * modifica proprio qui, e sovrascriverla farebbe perdere il lavoro.
     *
     * @return true se è stato ricaricato qualcosa
     */
    public boolean syncWithDatabase() {
        long version = database.getDataVersion();
        if (version == knownDataVersion) {
            return false;
        }
        knownDataVersion = version;
        reloadReservations();
        reloadUsers();
        refreshTableStatuses(LocalDateTime.now());
        fireEvent(ModelEvent.Type.RESERVATIONS_CHANGED, null);
        fireEvent(ModelEvent.Type.USERS_CHANGED, null);
        return true;
    }

    // ------------------------------------------------------------------
    // Autenticazione e permessi
    // ------------------------------------------------------------------

    /**
     * Verifica le credenziali. Ritorna l'utente autenticato oppure null.
     *
     * La password non viene mai confrontata direttamente: si ricalcola l'hash
     * con lo stesso sale memorizzato e si confrontano gli hash.
     */
    public User authenticate(String username, String plainPassword) {
        User user = userDao.findByUsername(username == null ? null : username.trim());
        if (user == null || !user.isActive()) {
            return null;
        }
        if (!PasswordHasher.matches(plainPassword, user.getPasswordHash())) {
            return null;
        }
        user.setLastLoginAt(LocalDateTime.now());
        userDao.update(user);
        this.currentUser = user;
        return user;
    }

    /** Chiude la sessione: la postazione torna alla schermata di accesso. */
    public void logout() {
        this.currentUser = null;
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(User user) {
        this.currentUser = user;
    }

    /**
     * Controllo dei permessi centralizzato.
     *
     * Le View disabilitano i pulsanti in base a questo metodo, ma i metodi che
     * modificano i dati lo richiamano comunque: un pulsante disabilitato è un
     * suggerimento per l'utente, non una garanzia di sicurezza.
     */
    public boolean can(Permission permission) {
        return currentUser != null && currentUser.can(permission);
    }

    private void require(Permission permission) throws ValidationException {
        if (!can(permission)) {
            throw new ValidationException("Permesso negato: "
                    + permission.getLabel().toLowerCase()
                    + ". Il tuo ruolo è "
                    + (currentUser == null ? "non autenticato" : currentUser.getRole().getLabel()) + ".");
        }
    }

    // ------------------------------------------------------------------
    // Utenti
    // ------------------------------------------------------------------

    public List<User> getUsers() {
        return Collections.unmodifiableList(users);
    }

    /**
     * Inserisce o aggiorna un utente.
     *
     * Oltre ai controlli sui campi ci sono due regole che proteggono il
     * sistema da chi lo amministra: non ci si può disattivare né cambiare
     * livello da soli, e deve sempre restare almeno un amministratore attivo.
     * Senza, bastava un clic sbagliato per chiudere fuori tutti.
     */
    public void saveUser(User user, String newPlainPassword) throws ValidationException {
        require(Permission.MANAGE_USERS);

        String username = user.getUsername() == null ? "" : user.getUsername().trim();
        if (username.isEmpty()) {
            throw new ValidationException("Il nome utente è obbligatorio.", "username");
        }
        if (username.chars().anyMatch(Character::isWhitespace)) {
            throw new ValidationException("Il nome utente non può contenere spazi.", "username");
        }
        user.setUsername(username);

        User existing = userDao.findByUsername(username);
        if (existing != null && existing.getId() != user.getId()) {
            throw new ValidationException("Esiste già un utente con questo nome.", "username");
        }
        if (user.getRole() == null) {
            throw new ValidationException("Scegli un livello di accesso.", "role");
        }

        boolean editingSelf = currentUser != null && user.getId() != 0 && user.getId() == currentUser.getId();
        if (editingSelf && !user.isActive()) {
            throw new ValidationException(
                    "Non puoi disattivare l'utente con cui hai effettuato l'accesso.", "active");
        }
        if (editingSelf && user.getRole() != currentUser.getRole()) {
            throw new ValidationException(
                    "Non puoi cambiare il tuo livello di accesso: chiedilo a un altro amministratore.", "role");
        }
        if (!leavesAnActiveAdmin(user)) {
            throw new ValidationException("Deve restare almeno un amministratore attivo.", "role");
        }

        if (newPlainPassword != null && !newPlainPassword.isBlank()) {
            if (newPlainPassword.length() < 6) {
                throw new ValidationException("La password deve avere almeno 6 caratteri.", "password");
            }
            user.setPasswordHash(PasswordHasher.hash(newPlainPassword));
        }
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new ValidationException("Imposta una password per il nuovo utente.", "password");
        }

        if (user.getId() == 0) {
            userDao.insert(user);
        } else {
            userDao.update(user);
        }
        if (editingSelf) {
            // L'utente della sessione è un oggetto separato: lo si allinea,
            // altrimenti la barra in alto mostrerebbe ancora il vecchio nome.
            currentUser.setFullName(user.getFullName());
            currentUser.setUsername(user.getUsername());
            currentUser.setPasswordHash(user.getPasswordHash());
        }
        reloadUsers();
        fireEvent(ModelEvent.Type.USERS_CHANGED, user);
    }

    public void deleteUser(User user) throws ValidationException {
        require(Permission.MANAGE_USERS);
        if (currentUser != null && currentUser.getId() == user.getId()) {
            throw new ValidationException("Non puoi eliminare l'utente con cui hai effettuato l'accesso.");
        }
        // Si guarda la versione memorizzata, non quella passata dalla vista,
        // che potrebbe essere una copia con modifiche non ancora salvate.
        User stored = findUserById(user.getId());
        if (stored != null && stored.getRole() == Role.ADMIN && stored.isActive()
                && countActiveAdminsExcept(stored.getId()) == 0) {
            throw new ValidationException("Deve restare almeno un amministratore attivo nel sistema.");
        }
        userDao.delete(user.getId());
        reloadUsers();
        fireEvent(ModelEvent.Type.USERS_CHANGED, user);
    }

    private User findUserById(int id) {
        for (User u : users) {
            if (u.getId() == id) {
                return u;
            }
        }
        return null;
    }

    private int countActiveAdminsExcept(int userId) {
        int n = 0;
        for (User u : users) {
            if (u.getId() != userId && u.getRole() == Role.ADMIN && u.isActive()) {
                n++;
            }
        }
        return n;
    }

    /** true se, salvando {@code changed}, resterebbe almeno un amministratore attivo. */
    private boolean leavesAnActiveAdmin(User changed) {
        if (changed.getRole() == Role.ADMIN && changed.isActive()) {
            return true;
        }
        return countActiveAdminsExcept(changed.getId()) > 0;
    }

    // ------------------------------------------------------------------
    // Prenotazioni
    // ------------------------------------------------------------------

    public List<Reservation> getReservations() {
        return Collections.unmodifiableList(reservations);
    }

    public List<Reservation> getReservationsForDate(LocalDate date) {
        List<Reservation> result = new ArrayList<>();
        for (Reservation r : reservations) {
            if (r.getDateTime() != null && r.getDateTime().toLocalDate().equals(date)) {
                result.add(r);
            }
        }
        Collections.sort(result);
        return result;
    }

    public Reservation findReservationById(int id) {
        for (Reservation r : reservations) {
            if (r.getId() == id) {
                return r;
            }
        }
        return null;
    }

    /** Prenotazione attiva su un tavolo in un dato istante. */
    public Reservation findReservationForTable(int tableId, LocalDateTime moment) {
        for (Reservation r : reservations) {
            if (r.getTableId() != tableId || !r.getStatus().occupiesTable()) {
                continue;
            }
            if (r.getDateTime() == null) {
                continue;
            }
            if (!moment.isBefore(r.getDateTime()) && moment.isBefore(r.getEndDateTime())) {
                return r;
            }
        }
        return null;
    }

    /** Prenotazioni ancora "vive" su un tavolo che finiscono dopo l'istante indicato. */
    public List<Reservation> findUpcomingReservationsForTable(int tableId, LocalDateTime from) {
        List<Reservation> result = new ArrayList<>();
        for (Reservation r : reservations) {
            if (r.getTableId() == tableId && r.getStatus().occupiesTable()
                    && r.getEndDateTime() != null && r.getEndDateTime().isAfter(from)) {
                result.add(r);
            }
        }
        Collections.sort(result);
        return result;
    }

    /**
     * Salva una prenotazione nuova o modificata.
     *
     * Può ricevere anche una COPIA di una prenotazione esistente (è quello
     * che fa la finestra di modifica): l'identità è data dall'id, e la copia
     * salvata prende il posto dell'oggetto precedente nell'elenco.
     */
    public void saveReservation(Reservation r) throws ValidationException {
        require(Permission.EDIT_RESERVATIONS);
        validate(r);

        if (currentUser != null && (r.getCreatedBy() == null || r.getCreatedBy().isBlank())) {
            r.setCreatedBy(currentUser.getUsername());
        }
        r.touch();

        if (r.getId() == 0) {
            reservationDao.insert(r);
            reservations.add(r);
        } else {
            reservationDao.update(r);
            for (int i = 0; i < reservations.size(); i++) {
                if (reservations.get(i).getId() == r.getId()) {
                    reservations.set(i, r);
                    break;
                }
            }
        }
        refreshTableStatuses(LocalDateTime.now());
        fireEvent(ModelEvent.Type.RESERVATIONS_CHANGED, r);
    }

    public void deleteReservation(Reservation r) throws ValidationException {
        require(Permission.DELETE_RESERVATIONS);
        reservationDao.delete(r.getId());
        // Per id e non per riferimento: chi chiama può avere in mano una copia.
        reservations.removeIf(existing -> existing.getId() == r.getId());
        refreshTableStatuses(LocalDateTime.now());
        fireEvent(ModelEvent.Type.RESERVATIONS_CHANGED, r);
    }

    /**
     * Controlli sui dati inseriti.
     *
     * Sono raccolti in un unico metodo, invocato sia dalla finestra di
     * inserimento sia da qualsiasi altro punto che salvi una prenotazione:
     * così una regola nuova entra in vigore ovunque scrivendola una volta sola.
     */
    public void validate(Reservation r) throws ValidationException {
        if (r.getGuestName() == null || r.getGuestName().isBlank()) {
            throw new ValidationException("Il nominativo è obbligatorio.", "guestName");
        }
        if (r.getDateTime() == null) {
            throw new ValidationException("Data e ora sono obbligatorie.", "dateTime");
        }
        // Solo per le prenotazioni NUOVE: una prenotazione di ieri deve poter
        // essere ancora aggiornata (segnata come completata o non presentata).
        if (r.getId() == 0 && r.getDateTime().toLocalDate().isBefore(LocalDate.now())) {
            throw new ValidationException(
                    "Non si può inserire una prenotazione per un giorno già passato.", "dateTime");
        }
        if (r.getDurationMinutes() < 15 || r.getDurationMinutes() > 12 * 60) {
            throw new ValidationException("La durata deve essere fra 15 minuti e 12 ore.", "duration");
        }
        if (r.getPartySize() < 1) {
            throw new ValidationException("Il numero di coperti deve essere almeno 1.", "partySize");
        }
        if (r.getPartySize() > 40) {
            throw new ValidationException("Per gruppi oltre 40 coperti serve una prenotazione evento.", "partySize");
        }
        if (r.getHighChairs() < 0 || r.getHighChairs() > r.getPartySize()) {
            throw new ValidationException(
                    "I seggioloni non possono superare il numero di coperti.", "highChairs");
        }
        if (r.getStrollerSpaces() < 0 || r.getStrollerSpaces() > 6) {
            throw new ValidationException("Numero di passeggini non plausibile.", "strollerSpaces");
        }
        if (r.getPhone() == null || r.getPhone().replaceAll("\\D", "").length() < 6) {
            throw new ValidationException(
                    "Inserisci un recapito telefonico valido (almeno 6 cifre).", "phone");
        }
        if (r.getEmail() != null && !r.getEmail().isBlank()
                && !EMAIL_PATTERN.matcher(r.getEmail().trim()).matches()) {
            throw new ValidationException("Indirizzo email non valido.", "email");
        }

        // I controlli sul tavolo valgono solo se la prenotazione lo occupa:
        // annullare o chiudere una prenotazione deve riuscire anche se nel
        // frattempo il suo tavolo è stato tolto o messo fuori servizio.
        if (r.getTableId() != 0 && r.getStatus().occupiesTable()) {
            RestaurantTable table = floorPlan.findTableById(r.getTableId());
            if (table == null) {
                throw new ValidationException("Il tavolo assegnato non esiste più in piantina.", "tableId");
            }
            if (!table.isReservable()) {
                throw new ValidationException(
                        "Il tavolo " + table.getNumber() + " è fuori servizio.", "tableId");
            }
            if (table.getSeats() < r.getPartySize()) {
                throw new ValidationException("Il tavolo " + table.getNumber() + " ha "
                        + table.getSeats() + " posti, insufficienti per "
                        + r.getPartySize() + " coperti.", "tableId");
            }
            List<Reservation> conflicts = findConflicts(r);
            if (!conflicts.isEmpty()) {
                Reservation c = conflicts.get(0);
                throw new ValidationException("Il tavolo " + table.getNumber()
                        + " è già occupato da " + c.getGuestName()
                        + " alle " + c.getDateTime().toLocalTime() + ".", "tableId");
            }
        }
    }

    /** Prenotazioni che occupano lo stesso tavolo in un intervallo sovrapposto. */
    public List<Reservation> findConflicts(Reservation candidate) {
        List<Reservation> conflicts = new ArrayList<>();
        if (candidate.getTableId() == 0) {
            return conflicts;
        }
        for (Reservation other : reservations) {
            if (other.getId() == candidate.getId() || other.getId() == 0) {
                continue;
            }
            if (other.getTableId() != candidate.getTableId()) {
                continue;
            }
            if (!other.getStatus().occupiesTable()) {
                continue;
            }
            if (candidate.overlaps(other)) {
                conflicts.add(other);
            }
        }
        return conflicts;
    }

    /**
     * Tavoli utilizzabili per questa prenotazione, ordinati dal più piccolo
     * che la contiene: assegnare un gruppo di 2 a una tavolata da 10 è uno
     * spreco che il sistema deve scoraggiare.
     */
    public List<RestaurantTable> findAvailableTables(Reservation candidate) {
        List<RestaurantTable> available = new ArrayList<>();
        for (RestaurantTable table : floorPlan.getTables()) {
            if (!table.isReservable() || table.getSeats() < candidate.getPartySize()) {
                continue;
            }
            Reservation probe = new Reservation();
            probe.setId(candidate.getId());
            probe.setTableId(table.getId());
            probe.setDateTime(candidate.getDateTime());
            probe.setDurationMinutes(candidate.getDurationMinutes());
            if (findConflicts(probe).isEmpty()) {
                available.add(table);
            }
        }
        available.sort((a, b) -> Integer.compare(a.getSeats(), b.getSeats()));
        return available;
    }

    // ------------------------------------------------------------------
    // Piantina
    // ------------------------------------------------------------------

    public FloorPlan getFloorPlan() {
        return floorPlan;
    }

    /**
     * Salva la piantina e riallinea le prenotazioni.
     *
     * Se è stato eliminato un tavolo, le prenotazioni che lo usavano
     * resterebbero legate a un id che non esiste più, e che un tavolo disegnato
     * in seguito potrebbe perfino riprendere. Al salvataggio vengono quindi
     * lasciate "da assegnare": una situazione visibile e correggibile, invece
     * di un collegamento sbagliato e silenzioso.
     *
     * @return quante prenotazioni sono rimaste senza tavolo
     */
    public int saveFloorPlan() throws ValidationException {
        require(Permission.EDIT_FLOOR_PLAN);
        floorPlanDao.save(floorPlan);

        int detached = 0;
        for (Reservation r : reservations) {
            if (r.getTableId() != 0 && floorPlan.findTableById(r.getTableId()) == null) {
                r.setTableId(0);
                r.touch();
                reservationDao.update(r);
                detached++;
            }
        }
        refreshTableStatuses(LocalDateTime.now());
        fireEvent(ModelEvent.Type.FLOOR_PLAN_CHANGED, floorPlan);
        if (detached > 0) {
            fireEvent(ModelEvent.Type.RESERVATIONS_CHANGED, null);
        }
        return detached;
    }

    /** Scarta le modifiche non salvate e torna alla piantina del database. */
    public void reloadFloorPlan() {
        FloorPlan loaded = floorPlanDao.loadFirst();
        if (loaded != null) {
            floorPlan.restoreFrom(loaded);
        }
        refreshTableStatuses(LocalDateTime.now());
        fireEvent(ModelEvent.Type.FLOOR_PLAN_CHANGED, floorPlan);
    }

    public void notifyFloorPlanChanged() {
        fireEvent(ModelEvent.Type.FLOOR_PLAN_CHANGED, floorPlan);
    }

    /**
     * Ricalcola lo stato di ogni tavolo a partire dalle prenotazioni.
     *
     * Lo stato non è un dato inserito a mano ma una CONSEGUENZA delle
     * prenotazioni: OCCUPATO se il cliente è già arrivato ed è nella sua fascia
     * oraria, PRENOTATO se il tavolo è impegnato adesso o più tardi nella
     * stessa giornata, LIBERO altrimenti. Tenerlo derivato evita che la
     * piantina e l'elenco prenotazioni possano raccontare due storie diverse.
     *
     * Gli stati DA_PULIRE e FUORI_SERVIZIO sono invece decisi dall'operatore
     * (RestaurantTable.getServiceStatus) e hanno sempre la precedenza.
     */
    public void refreshTableStatuses(LocalDateTime moment) {
        LocalDate day = moment.toLocalDate();
        for (RestaurantTable table : floorPlan.getTables()) {
            Reservation active = null;   // in corso adesso
            Reservation upcoming = null; // più avanti nella stessa giornata

            for (Reservation r : reservations) {
                if (r.getTableId() != table.getId() || r.getDateTime() == null) {
                    continue;
                }
                if (!r.getStatus().occupiesTable()) {
                    continue;
                }
                if (!r.getDateTime().toLocalDate().equals(day)) {
                    continue;
                }
                if (!moment.isBefore(r.getDateTime()) && moment.isBefore(r.getEndDateTime())) {
                    active = r;
                } else if (r.getDateTime().isAfter(moment)
                        && (upcoming == null || r.getDateTime().isBefore(upcoming.getDateTime()))) {
                    upcoming = r;
                }
            }

            if (active != null && active.getStatus() == ReservationStatus.ARRIVATA) {
                table.setLiveStatus(TableStatus.OCCUPATO);
            } else if (active != null || upcoming != null) {
                table.setLiveStatus(TableStatus.PRENOTATO);
            } else {
                table.setLiveStatus(TableStatus.LIBERO);
            }
        }
    }

    // ------------------------------------------------------------------
    // Comande
    // ------------------------------------------------------------------

    public List<Ticket> getTickets() {
        return Collections.unmodifiableList(tickets);
    }

    public List<Ticket> getOpenTickets() {
        List<Ticket> open = new ArrayList<>();
        for (Ticket t : tickets) {
            if (t.getStatus().isOpen()) {
                open.add(t);
            }
        }
        Collections.sort(open);
        return open;
    }

    /**
     * Inserisce o aggiorna una comanda arrivata dalla rete.
     *
     * L'identità è data dal codice: il server assegna un codice univoco e i
     * client si limitano a rispecchiarlo, quindi due postazioni non possono
     * creare due copie divergenti della stessa comanda.
     */
    public void upsertTicket(Ticket ticket) {
        for (int i = 0; i < tickets.size(); i++) {
            if (tickets.get(i).getCode().equals(ticket.getCode())) {
                tickets.set(i, ticket);
                fireEvent(ModelEvent.Type.TICKETS_CHANGED, ticket);
                return;
            }
        }
        tickets.add(ticket);
        fireEvent(ModelEvent.Type.TICKETS_CHANGED, ticket);
    }

    public void replaceTickets(List<Ticket> incoming) {
        tickets.clear();
        if (incoming != null) {
            tickets.addAll(incoming);
        }
        fireEvent(ModelEvent.Type.TICKETS_CHANGED, null);
    }

    public Database getDatabase() {
        return database;
    }
}
