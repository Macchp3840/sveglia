# Sveglia Dura ⏰

App Android con una sveglia che **non si spegne con un dito**: per farla smettere devi alzarti
e completare delle missioni.

## Cosa fa (versione 0.1)

| Funzione | Come funziona |
|---|---|
| **Missione calcoli** | N operazioni tipo `37 × 7 + 58`. Se sbagli ne arriva un'altra. |
| **Missione scuoti** | Scuoti forte il telefono N volte (accelerometro). |
| **Missione codice a barre / QR** | Registri un codice lontano dal letto (dentifricio, caffè, QR attaccato in bagno). Per spegnere la sveglia devi andare lì a scansionarlo. È la missione più efficace. |
| **Volume bloccato** | Il volume della sveglia viene riportato al massimo ogni secondo, anche se lo abbassi. |
| **Niente snooze, niente tasto indietro** | Il tasto indietro non chiude la schermata. Se esci con Home, la sveglia continua a suonare. |
| **Controllo anti-ricaduta** | X minuti dopo averla spenta ti chiede "Sei sveglio?". Se non rispondi entro 60 secondi, ricomincia a suonare con tutte le missioni. |
| **Anti-riavvio** | Se spegni o riavvii il telefono mentre suona, ricomincia appena lo sblocchi. |
| **Sopra la schermata di blocco** | Accende lo schermo e compare a schermo intero. |
| **Uscita d'emergenza** | Se non trovi il codice, puoi sostituirlo con 8 calcoli: scomodi, ma non resti bloccato. |

## Come ottenere l'APK

Ogni push compila l'app con GitHub Actions (workflow `Build APK`). Apri la scheda **Actions** del
repository, scegli l'ultima esecuzione e scarica l'artifact `sveglia-debug-apk`. Poi installalo
sul telefono (va consentita l'installazione da origini sconosciute).

In locale, con Android Studio o con l'SDK Android installato:

```bash
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/
```

Requisiti: Android 8.0 o successivo. Per la scansione dei codici servono i Google Play Services.

## Primo avvio

1. Concedi le notifiche (senza di quelle la sveglia non può comparire sullo schermo).
2. Se compare un avviso rosso in alto, premi **Risolvi** e concedi il permesso indicato.
3. Imposta l'ora, scegli le missioni e premi **Salva**.
4. Usa **Prova: suona tra 10 secondi** e blocca il telefono per vedere come funziona davvero.

## Limiti noti

- Spegnere il telefono, fare *forza arresto* o disinstallare l'app ferma la sveglia. Nessuna app
  normale può impedirlo (il riavvio però la fa ripartire, vedi sopra).
- La missione "scuoti" si può fare anche dal letto. Per alzarti davvero usa il codice a barre.
- Con il telefono bloccato, la scansione del codice chiede di sbloccare lo schermo.
- Alcune marche (Xiaomi, Huawei, Oppo...) uccidono le app in background in modo aggressivo:
  conviene togliere l'ottimizzazione batteria per questa app.

## Idee per le prossime versioni

- **Foto di un luogo**: fai una foto del lavandino e la sveglia si spegne solo se la foto corrisponde.
- **Passi**: cammina N passi (sensore contapassi).
- **Memory / sequenza di colori** da ripetere, oppure frase lunga da riscrivere senza errori.
- **Più sveglie** con missioni diverse (es. weekend più leggero).
- **Blocco modifiche serali**: dopo una certa ora non puoi più spostare o disattivare la sveglia di domani.
- **Riapertura forzata**: con il permesso "mostra sopra altre app" la schermata si riapre se esci.
- **Statistiche** e serie di giorni in cui ti sei alzato subito.
- **Penalità sociale** (facoltativa): se non completi le missioni entro 15 minuti, parte un messaggio a un amico.
