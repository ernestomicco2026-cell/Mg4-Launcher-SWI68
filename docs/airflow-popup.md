# Climate submenu / Sottomenu Clima

EN: Open the existing Climate submenu for eight controls in three rows:
A/C, AUTO and recirculation; windscreen, face and feet airflow; front demist
and rear-window heating. The front demist tile replaces the previous one.
Tapping the fan still opens MG4 Clima or the original HVAC controls. The bar
arrows adjust fan speed and holding the fan switches climate power on/off.
The submenu closes outside, with Back, or after six seconds without touches.
It scrolls when needed, including at larger control sizes.

IT: Apri il sottomenu Clima esistente per otto comandi in tre righe:
A/C, AUTO e ricircolo; aria a parabrezza, viso e piedi; disappannamento
anteriore e lunotto termico. Il comando anteriore sostituisce quello precedente.
Il tocco sulla ventola apre ancora MG4 Clima o i comandi originali. Le frecce
regolano la velocità e la pressione lunga accende/spegne il clima.
Il sottomenu si chiude toccando fuori, con Indietro o dopo sei secondi senza tocchi.
Scorre se necessario, anche con comandi grandi.

EN: Windscreen airflow directs air to the front glass. Front demist is a separate
vehicle mode. Rear window heating controls the electric rear-window heater.
Direction combinations follow the car's seven modes; the final active outlet
cannot be turned off. Unknown controls remain visible but disabled. Outlet and
rear heating highlights use confirmed vehicle readings, not predicted success.

IT: La direzione parabrezza manda aria al vetro anteriore. Il disappannamento
anteriore è una modalità separata dell'auto. Il lunotto termico comanda il
riscaldamento elettrico posteriore. Le combinazioni seguono i sette modi dell'auto;
l'ultima direzione attiva non si può spegnere. I comandi sconosciuti restano
visibili ma disabilitati. Direzioni e lunotto usano le letture confermate
dell'auto per evidenziare lo stato, senza presumere il successo del comando.

EN: Protocol reference: [MG4 Clima SaicBackend](https://github.com/ernestomicco2026-cell/HVAC-Clima-MG4/blob/729b5ec090ba0a4f2d958734bff7d075848770c2/MG4Clima/app/java/com/mg4/clima/SaicBackend.java)
and its `Comandi.direction` / `HvacClient.State.directions` mapping. SAIC direction
set/read transactions are 14/58; rear heating open/close/read are 50/51/93.
Front demist retains the launcher's existing 48/49/92 commands. These are SAIC
codes for this SWI68 repository; other variants must use their own backend while
keeping the same appearance. Compilation and outlet-mode tests do not verify
behaviour on the vehicle. MG4 Clima also notes that window-clearing commands
need vehicle confirmation.

IT: Riferimento protocollo: `SaicBackend`, `Comandi.direction` e
`HvacClient.State.directions` di MG4 Clima, collegato sopra. Le transazioni SAIC
scrittura/lettura direzione sono 14/58; accensione/spegnimento/lettura lunotto sono
50/51/93. Il disappannamento anteriore mantiene i comandi esistenti 48/49/92.
Sono codici SAIC per questo repository SWI68; le altre varianti devono usare il
proprio backend mantenendo la stessa grafica. Compilazione e test delle direzioni
non verificano il comportamento in auto. Anche MG4 Clima richiede conferma in
auto per i comandi di disappannamento.
