# RistoManager

[![CI](https://github.com/d4b0mb/progettoIGPE/actions/workflows/ci.yml/badge.svg)](https://github.com/d4b0mb/progettoIGPE/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)

Sistema di gestione per ristorante: prenotazioni, piantina della sala modificabile
e comunicazione in tempo reale fra sala e cucina.

> **Nota:** questo è un progetto sperimentale, **non** il lavoro che consegno
> per l'esame. L'ho scritto per vedere concretamente cosa produce
> un'implementazione assistita da un'AI, prendendo come spunto gli argomenti
> del corso universitario **Interfacce Grafiche e Programmazione ad Eventi**
> (Java, Swing, JDBC/SQLite, thread e socket). Il progetto che uso per il
> corso è un lavoro separato, scritto autonomamente.

---

## Avvio rapido

### Da Eclipse

1. `File → Import… → Maven → Existing Maven Projects`, selezionare questa cartella.
2. Attendere che Maven scarichi le dipendenze (FlatLaf, sqlite-jdbc, jbcrypt e,
   solo per i test, JUnit 5).
3. Tasto destro su `Main.java` → `Run As → Java Application`.

Serve un JDK 17 o superiore.

### Da riga di comando

```bash
mvn clean package
java -jar target/ristomanager.jar
```

`mvn clean package` esegue anche i test; per saltarli si aggiunge `-DskipTests`.

### Dimostrazione completa (sala + cucina)

L'applicazione è pensata per girare in **più copie contemporaneamente**.
Aprire tre terminali:

```bash
java -jar target/ristomanager.jar server     # il server delle comande
java -jar target/ristomanager.jar sala       # la postazione di sala
java -jar target/ristomanager.jar cucina     # il monitor di cucina
```

Oppure avviare tre volte il programma senza argomenti e scegliere il ruolo dalla
schermata iniziale. L'ordine di avvio non conta: una postazione aperta prima del
server si collega da sola appena il server parte, e si ricollega se il server
viene fermato e riavviato.

Le postazioni possono stare anche su macchine diverse; la finestra del server
mostra l'indirizzo da usare. Fra macchine diverse viaggiano in tempo reale le
comande; prenotazioni, piantina e utenti restano invece nel `ristorante.db` di
ciascuna macchina. Sullo stesso computer le postazioni condividono lo stesso file
e si allineano da sole.

```bash
java -jar target/ristomanager.jar server 8421
java -jar target/ristomanager.jar sala 192.168.1.50 8421
```

Il database SQLite (`ristorante.db`) viene creato al primo avvio nella cartella
di lavoro e popolato con una sala già arredata e una giornata di prenotazioni.
Le prenotazioni di esempio hanno la data del primo avvio: per riaverle con la
data di oggi si usa **Ripristina dati di esempio…** nella schermata iniziale
(cancella tutti i dati, quindi va fatto con le altre postazioni chiuse).

---

## Utenti di prova

| Utente     | Password    | Livello | Cosa può fare                                        |
|------------|-------------|---------|------------------------------------------------------|
| `admin`    | `admin123`  | Tier 1  | Tutto, compresa la piantina e la gestione utenti     |
| `mrossi`   | `mario123`  | Tier 2  | Inserire e modificare prenotazioni, sala e cucina    |
| `gbianchi` | `giulia123` | Tier 2  | Come sopra                                           |
| `stage`    | `stage123`  | Tier 3  | Sola lettura                                         |

Nella schermata di accesso un clic sulla riga di un utente di prova compila nome
utente e password. Le password non sono memorizzate in chiaro: nel database c'è
solo l'hash BCrypt.

---

## Cosa contiene

### Prenotazioni
Nominativo, orario e durata, coperti, **seggioloni** e **spazi passeggino**,
telefono, email e codice fedeltà. Allergeni selezionabili fra i 14 obbligatori
per legge. Assegnazione del tavolo con controllo automatico di capienza e
sovrapposizione oraria: la tendina propone solo i tavoli davvero liberi in quella
fascia e dice quanti sono; se il tavolo già scelto smette di andare bene (più
coperti, altro orario) lo segnala invece di cambiarlo in silenzio. Ricerca,
ordinamento su ogni colonna, navigazione per giornata e azioni rapide: conferma,
arrivato, completa, disdici, no-show.

Una modifica rifiutata dalla validazione non lascia dati "a metà" nell'elenco:
la finestra lavora su una copia della prenotazione. Le prenotazioni inserite da
un'altra postazione sullo stesso computer compaiono da sole entro pochi secondi.

### Piantina della sala
Editor 2D vista dall'alto, disegnato con `Graphics2D`.

- La sala è **ridimensionabile in metri reali** (larghezza e profondità).
- Tavoli tondi, quadrati e rettangolari: si disegnano trascinando, si spostano,
  si ridimensionano dalle otto maniglie e si ruotano di qualunque angolo.
- Elementi non prenotabili: muri, colonne, porte, finestre, scale, cucina,
  bagni, bancone, ingresso, deposito.
- Ogni elemento mostra **misure reali, rapporto fra i lati e percentuale di
  superficie occupata**; le sedie vengono disegnate attorno al tavolo in scala.
- Griglia con aggancio, righelli graduati, zoom sul puntatore, barra di scala,
  posizione del puntatore in metri.
- Segnalazione automatica degli elementi che escono dalla sala, si sovrappongono
  o hanno lo stesso numero di un altro tavolo.
- **Annulla / ripeti** e indicatore delle modifiche non salvate: chiudendo la
  sessione con modifiche in sospeso il programma chiede se salvarle.
- Il colore dei tavoli deriva dalle prenotazioni (libero, prenotato, occupato);
  l'operatore decide solo la disponibilità: in servizio, da pulire, fuori servizio.
- Eliminare un tavolo con prenotazioni in programma chiede conferma; al
  salvataggio quelle prenotazioni restano "da assegnare" e vengono segnalate.

Comandi: trascina per disegnare · `Esc` torna a «Seleziona» · rotella per lo zoom ·
tasto destro per spostare la vista · frecce per spostare · `R` per ruotare ·
`Canc` per eliminare · `Ctrl+D` duplica · `Ctrl+Z` / `Ctrl+Y` annulla e ripeti ·
`Ctrl+S` salva.

### Sala ↔ cucina
Vera architettura **client/server su socket**. La sala compone la comanda dal
menu e la invia; il server le assegna un codice progressivo, la salva e la
ritrasmette a tutte le postazioni collegate; la cucina la vede comparire senza
alcun aggiornamento manuale e ne cambia lo stato, che torna indietro fino alla
sala.

- Le **allergie dichiarate dal cliente** nella prenotazione viaggiano con la
  comanda, tenute separate dagli allergeni contenuti nei piatti. In sala i piatti
  che il cliente non può mangiare sono in rosso già nel menu e l'invio chiede
  conferma; in cucina la banda rossa in cima al biglietto elenca le allergie e i
  piatti in conflitto sono scritti in rosso.
- Colore diverso per ogni livello di importanza: normale, alta, urgente, VIP.
- Cronometro per ogni comanda, che diventa rosso oltre i 15 minuti.
- Marcature temporali di invio, presa in carico, pronta e servita.
- Notifica a comparsa, con segnale acustico, per una comanda nuova in cucina e
  per una comanda pronta in sala.
- Riconnessione automatica al server; il server segnala subito se la sua porta
  è già occupata e può essere fermato e riavviato dalla sua finestra.

### Livelli di accesso
Tier 1 amministratore, Tier 2 operatore, Tier 3 sola lettura. I permessi sono
definiti una sola volta in `Role`; l'interfaccia nasconde o disabilita i comandi
non consentiti e il Model ripete comunque il controllo prima di scrivere.
Un amministratore non può disattivarsi, cambiarsi livello né eliminarsi, e nel
sistema resta sempre almeno un amministratore attivo.

### Altro
- Tema chiaro e scuro, cambiabile al volo dalla barra laterale; la scelta viene
  ricordata per i successivi avvii.
- "Esci" permette di cambiare utente senza chiudere la postazione.
- Scorciatoie: `Ctrl+1…5` per le sezioni, `Ctrl+N` nuova prenotazione, `Ctrl+F`
  cerca, `Invio` apre la prenotazione selezionata, `Esc` chiude le finestre.
- Un errore imprevisto viene mostrato in una finestra invece di perdersi nella
  console.

---

## Test

I test JUnit 5 in `src/test/java` verificano Model, persistenza e rete senza
aprire finestre, ognuno su un database temporaneo:

- regole sulle prenotazioni: campi obbligatori, date passate, capienza,
  sovrapposizioni, tavoli fuori servizio, stato dei tavoli;
- permessi applicati dal Model e protezione dell'ultimo amministratore;
- geometria della piantina e istantanee di annulla/ripeti;
- allergie del cliente, ordinamento della coda di cucina, serializzazione;
- aggiornamento di un database creato dalla versione precedente e ripristino
  dei dati di esempio;
- sala e cucina collegate da socket veri: andata e ritorno di una comanda,
  60 comande inviate in parallelo da quattro postazioni, porta occupata,
  riconnessione automatica.

Da Eclipse: tasto destro su `src/test/java` → `Run As → JUnit Test`.
Da riga di comando: `mvn test`.

---

## Struttura del codice

```
it.unical.igpe.ristorante
├── Main, AppConfig              avvio e configurazione della postazione
├── model/                       dati e regole di dominio (nessuna classe Swing)
│   ├── floor/                   piantina: FloorElement astratta, tavoli, ostacoli
│   └── kitchen/                 comande, righe, priorità, stati
├── persistence/                 SQLite via JDBC: Database, DAO, hashing password
├── net/                         protocollo, KitchenServer, KitchenClient
└── view/                        Swing
    ├── common/                  tema, colori, componenti riutilizzabili, notifiche
    ├── reservations/            elenco, dettaglio e finestra di inserimento
    ├── floor/                   tela di disegno e pannello proprietà
    ├── kitchen/                 invio comande e monitor di cucina
    └── users/                   gestione degli utenti

src/test/java                    test JUnit 5 di model, persistence e net
```

L'architettura segue il pattern **MVC**: il `Model` non conosce l'interfaccia e
si limita a notificare i cambiamenti ai `ModelListener` registrati; le viste si
aggiornano da sole leggendo il Model. I test lo usano senza nessuna finestra.

### Argomenti di programmazione toccati

Per riferimento: come le funzionalità sopra si mappano ai concetti di un tipico
corso di interfacce grafiche e programmazione a eventi.

| Argomento                     | Dove                                                        |
|-------------------------------|-------------------------------------------------------------|
| Strutture dati                | `List`, `Map`, `EnumSet`, `Deque`, `Comparator`, `Collections` |
| Ereditarietà e polimorfismo   | `FloorElement` astratta → `RestaurantTable`, `Obstacle`      |
| Gestione degli errori         | `ValidationException` (controllata), `DataAccessException`   |
| Swing e layout                | `BorderLayout`, `BoxLayout`, `GridLayout`, `CardLayout`      |
| Programmazione a eventi       | `ActionListener`, `MouseListener`, `KeyListener`, key bindings, `Timer` |
| Disegno personalizzato        | `paintComponent` + `Graphics2D` in tutta la piantina         |
| Modelli di componenti         | `AbstractTableModel`, `TableCellRenderer`, `ListCellRenderer`|
| Input/Output e serializzazione| `ObjectOutputStream` sui socket e per le istantanee di annulla/ripeti |
| Database                      | JDBC, `PreparedStatement`, transazioni, BCrypt, aggiornamento dello schema |
| Reti                          | `ServerSocket` / `Socket`, protocollo a messaggi, riconnessione |
| Concorrenza                   | thread per client, `SwingUtilities.invokeLater`, `synchronized`, `volatile`, `AtomicBoolean` |
| Test                          | JUnit 5, compresi test di rete con più client in parallelo   |

---

## Dipendenze

| Libreria      | Versione   | A cosa serve                                        |
|---------------|------------|-----------------------------------------------------|
| FlatLaf       | 3.5.2      | Look and Feel moderno per Swing (solo aspetto)      |
| sqlite-jdbc   | 3.46.1.3   | Driver JDBC per SQLite                              |
| jbcrypt       | 0.4        | Hashing delle password                              |
| JUnit Jupiter | 5.14.4     | Solo per i test, non entra nel jar eseguibile       |

FlatLaf non cambia il codice: sostituisce il modo in cui i componenti Swing
standard vengono disegnati. Le classi usate restano `JPanel`, `JButton`,
`JTable`, `JComboBox`.

---

## Licenza

Distribuito sotto licenza [MIT](LICENSE).
