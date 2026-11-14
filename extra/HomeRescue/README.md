# HomeRescue: soccorso della home MG originale

**Problema (issue #29):** con "Avvio sicuro" il launcher disattiva la schermata principale della home MG. Se il launcher viene tolto dal menu App di Android, non può riaccenderla, e l'auto resta su "Smartphone starting".

**Soluzione:** `HomeRescue.java` è una classe autonoma, senza dipendenze dal launcher, da mettere nelle altre app MG4 firmate con la chiave platform: MG4 Store, MG4 Clima, MG4 Voice e l'app di soccorso MG4 Ripristino Home. Quando il launcher viene rimosso, una di queste app riaccende la home MG.

## Componenti della home MG

| Firmware | Pacchetto | Attività principale |
|---|---|---|
| SWI68, SWI133 | `com.saicmotor.hmi.launcher` | `com.saicmotor.hmi.launcher.ui.MainActivity` |
| SWI69, SWI132 | `com.saicmotor.launcher` | `com.saicmotor.launcher.view.MainActivity` |

Fonti: `HomeDefault.MG` / `MgApps.HOME_PKG`, `HOME_CLS` nei repository delle quattro versioni.

Per i firmware non ancora conosciuti, la classe riaccende anche qualunque schermata HOME di `com.saicmotor.*` rimasta disattivata.

## Come funziona

1. **Quando parte.** Il receiver nel manifest ascolta:
   - `PACKAGE_FULLY_REMOVED` per `com.mg4.launcher` (esente dai limiti dei broadcast impliciti di Android 8+);
   - `BOOT_COMPLETED` (e `MY_PACKAGE_REPLACED`).
2. **Quando non fa niente:**
   - se `com.mg4.launcher` è ancora installato (comanda lui);
   - se c'è un altro launcher installato (per esempio MG4Dash), che gestisce da sé la home MG.
3. **Cosa fa:**
   - riattiva la home MG (`COMPONENT_ENABLED_STATE_DEFAULT`);
   - la rimette come home predefinita (`replacePreferredActivity`);
   - controlla che non sia più disattivata.
4. **Log:**
   - scrive una riga con `android.util.Log` (tag `HomeRescue`);
   - la passa a `HomeRescue.sink`, se l'app ne ha impostato uno;
   - la salva in SharedPreferences `mg4homerescue` / `ultimo`, con data e ora.

   Le righe hanno questa forma:
   - `home MG ok (android.intent.action.PACKAGE_FULLY_REMOVED) · com.saicmotor.hmi.launcher riattivata · predefinita: com.saicmotor.hmi.launcher (rimessa)`
   - `launcher presente: niente da fare (…)`
   - `c'è un altro launcher (…): niente da fare (…)`
   - `home MG NON ripristinata (…)`

   All'accensione normale (home già attiva) non scrive niente.

## Come includerla in un'altra app

1. Copia `HomeRescue.java` nei sorgenti dell'app, con lo stesso package `com.mg4.homerescue`. Non serve altro codice.
2. Nel manifest aggiungi:

```xml
<uses-permission android:name="android.permission.CHANGE_COMPONENT_ENABLED_STATE" />
<uses-permission android:name="android.permission.SET_PREFERRED_APPLICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver android:name="com.mg4.homerescue.HomeRescue" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.PACKAGE_FULLY_REMOVED" />
        <data android:scheme="package" />
    </intent-filter>
</receiver>
```

3. Se vuoi la riga anche nel log dell'app, collega `HomeRescue.sink`, per esempio in `Application.onCreate`:
   `HomeRescue.sink = (k, v) -> Diag.put(k, v);`
   Oppure leggi `HomeRescue.last(context)` quando prepari il log.
4. L'app deve essere firmata con la chiave platform: i due permessi qui sopra si ottengono solo con la firma.
5. Android non consegna i broadcast a un'app appena installata finché non è stata aperta almeno una volta.

## App di soccorso

`extra/ripristino/` contiene **MG4 Ripristino Home** (`com.mg4.homerescue`, circa 25 KB):
- si compila con `extra/ripristino/build.sh` e il risultato è `build/MG4HomeRescue-1.0.apk`;
- quando si apre, riaccende la home MG su tutti i firmware conosciuti, la rimette come predefinita e mostra l'esito in 8 lingue;
- poi offre "Apri la home originale" e "Disinstalla questa app";
- funziona anche se il launcher non c'è più.
