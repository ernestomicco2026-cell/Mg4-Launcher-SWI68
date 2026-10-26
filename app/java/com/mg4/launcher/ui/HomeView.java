package com.mg4.launcher.ui;

import android.content.Context;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.svc.CarFeatures;
import com.mg4.launcher.svc.MediaClient;
import com.mg4.launcher.svc.NavClient;
import com.mg4.launcher.svc.VehicleClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home in stile Tesla (MG4, SWI68): auto + energia a sinistra, pannello Mappa/Media/CarPlay a destra, barra in basso. */
public final class HomeView extends LinearLayout {

    /** Azioni che la home chiede all'activity. */
    public interface Host {
        void openApps();

        void openSettings();

        void launch(String pkg, String cls);

        /** Gestione energia dell'auto su una scheda: "ChargeManagementFragment" o "DischargeManagementFragment" (V2L). */
        void openEnergy(String module);

        void launchNavigator();

        void launchNavigator(String which);

        java.util.List<com.mg4.launcher.apps.AppCatalog.App> otherNavApps();

        /** False se l'app originale non esiste su questo firmware (il pulsante si disattiva). */
        boolean canLaunch(String pkg, String cls);

        /** Contenitore a tutto schermo per le conferme. */
        FrameLayout overlay();
    }

    private static final int TAB_MAP = 0, TAB_MEDIA = 1, TAB_PHONE = 2;

    private final Theme t;
    private final Prefs prefs;
    private final Host host;
    private final VehicleClient vehicle;
    private final MediaClient media;
    private final NavClient nav;

    // pannello auto
    private TextView clockTv, dateTv;
    private TextView acBtn, autoBtn;
    private FrameLayout frontFogBtn, rearFogBtn, recircBtn;
    private TextView rangeTv, rangeUnitTv, socTv, statusTv;
    private ImageView statusIcon;
    // pulsanti di ricarica (opzione)
    private LinearLayout chargeRow;
    private View limitBtn, currentBtn, startBtn;
    private TextView limitVal, currentVal, startLabel, startVal;
    private VehicleClient.State lastVehicle = new VehicleClient.State();
    private Bars.Progress battery;
    private CarView car;

    // pannello destro
    private FrameLayout right;
    private View mapPanel, mediaPanel, phonePanel;
    private TextView tabMap, tabMedia, tabPhone;
    private LinearLayout connRow;
    private ImageView connBt, connWifi;
    private TextView connNet;
    private Bars.Signal connBars;
    private int tab = TAB_MEDIA;
    private boolean userPickedTab;
    private boolean wasNavigating;

    // mappa
    private LinearLayout navIdle, navCard, navEta;
    private TextView navCardTitle, navCardSub, navEtaArrive, navEtaMin, navEtaKm;
    private View navHomeBtn, navOfficeBtn;
    private LinearLayout navWith, navWithBox, navPlaces;
    private TextView navDefaultTv;
    private boolean navWithPhone;

    // media
    private LinearLayout srcRow, stationPanel, stationList, stationEmpty;
    private android.widget.ScrollView stationScroll;
    private TextView stationTitle, stationCount;
    private String stationSignature = "";
    private int stationBand = -1;
    private String srcSignature = "";
    private int pendingSource, pendingBand;
    private long pendingSourceUntil, pendingBandUntil;
    private TextView mTitle, mSub, mMeta, mPos, mDur, artFreq, artUnit, artBand;
    private ImageView artIcon, artLogo;
    private LinearLayout artRadio, progressRow, bandRow, favRow;
    private View favScroll;
    private String favSignature = "";
    private Bars.Progress mProgress;
    private ImageView playIcon, miniPlayIcon;
    private TextView[] bandChips;

    // telefono
    private TextView phoneTitle, phoneSub, phoneNavDist, phoneNavRoad;
    private LinearLayout phoneNav;

    // barra
    private TextView tempTv, miniTitle, miniSub;
    private ImageView miniArt;

    private int acTemp = -1;
    private int fanLevel = -1, fanMin = 1, fanMax = 11;
    private long localTempUntil, localFanUntil;
    private TextView fanTv, outTv, inTv;
    private LinearLayout tpmsRow, tpmsFront, tpmsRear;
    private FrameLayout seatLeftBtn, seatRightBtn, wheelBtn;
    // 0.8.2: gruppi Clima / Comfort, LKA e OPD
    private boolean grouped;
    private FrameLayout climaGroup, comfortGroup;
    private LinearLayout climaDots, comfortDots;
    /** Gruppo Guida (0.9.5): LKA, One Pedal, assistente ACC/TJA e pedale del freno in un solo pulsante. */
    private FrameLayout guidaGroup, projBtn;
    private LinearLayout guidaDots;
    private int recircIcon;
    private View barView;
    private GroupPopup popup;
    private ImageView outIcon;

    /** Spazio lasciato libero in alto a sinistra quando la barra di sistema disegna la spia luci. */
    private static final int SYSTEM_LIGHT_INSET = 64;
    /** Home specchiata (guida a destra) e spia luci di sistema presente. */
    private final boolean rhd, sysIndicator;
    /** Barra in basso grande (opzione): misure fisse in px, indipendenti da "Dimensione comandi". */
    private final boolean big;

    public HomeView(Context c, Theme t, Prefs prefs, Host host, VehicleClient vehicle, MediaClient media, NavClient nav) {
        super(c);
        this.t = t;
        this.prefs = prefs;
        this.host = host;
        this.vehicle = vehicle;
        this.media = media;
        this.nav = nav;
        setOrientation(VERTICAL);
        setBackgroundColor(t.bg);
        int pad = dp(20);
        setPadding(pad, pad, pad, pad);

        // guida a destra: dall'impostazione, oppure come l'auto
        boolean carRhd = CarFeatures.isRightHandDrive(c);
        String side = prefs.driveSide();
        rhd = Prefs.DRIVE_RIGHT.equals(side) || (Prefs.DRIVE_AUTO.equals(side) && carRhd);
        sysIndicator = CarFeatures.hasSystemLightIndicator(c) || carRhd;
        big = prefs.bigBar();

        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.FILL_VERTICAL);
        addView(top, Ui.lpWeight(LayoutParams.MATCH_PARENT, 0, 1f));
        View carPanel = buildCarPanel(c);
        right = buildRightPanel(c);
        if (!rhd) {
            top.addView(carPanel, new LayoutParams(dp(720), LayoutParams.MATCH_PARENT));
            top.addView(right, Ui.margins(Ui.lpWeight(0, LayoutParams.MATCH_PARENT, 1f), dp(16), 0, 0, 0));
        } else {
            // auto e clima dal lato del guidatore
            top.addView(right, Ui.lpWeight(0, LayoutParams.MATCH_PARENT, 1f));
            top.addView(carPanel, Ui.margins(new LayoutParams(dp(720), LayoutParams.MATCH_PARENT), dp(16), 0, 0, 0));
        }
        addView(buildBottomBar(c), Ui.margins(new LayoutParams(LayoutParams.MATCH_PARENT, big ? dp(BIG_BAR) : tp(100)), 0, dp(14), 0, 0));

        applyCarLook();
        selectTab(TAB_MEDIA, false);
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    /** Misura di un comando toccabile (cresce con la dimensione comandi). */
    private int tp(float v) {
        return Ui.tp(getContext(), v);
    }

    /** Testo di un comando toccabile. */
    private static float ts(float sp) {
        return Ui.ts(sp);
    }

    /** True se la home è specchiata per la guida a destra. */
    public boolean isRightHand() {
        return rhd;
    }

    public void refreshNav() {
        buildNavWith(getContext(), navWithPhone);
    }

    public void applyCarLook() {
        car.setLook(Prefs.CAR_COLOR_VALUES[prefs.carColorIndex()], prefs.blackRoof(), prefs.wheelIndex(), t);
    }

    // =========================================================================================
    // Pannello auto
    // =========================================================================================

    private View buildCarPanel(Context c) {
        LinearLayout p = Ui.column(c);
        p.setBackground(Ui.pressable(c, t.panel, 24, t));
        p.setPadding(dp(32), dp(28), dp(32), dp(24));
        p.setClickable(true);
        p.setOnClickListener(v -> host.openEnergy("ChargeManagementFragment"));
        p.setOnLongClickListener(v -> {
            host.openSettings();
            return true;
        });

        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.BOTTOM);
        rangeTv = Ui.text(c, "—", 76, t.text, Ui.LIGHT);
        rangeTv.setLetterSpacing(-0.03f);
        head.addView(rangeTv);
        rangeUnitTv = Ui.text(c, prefs.socBig() ? "%" : "km", 26, t.sub, Ui.REGULAR);
        head.addView(rangeUnitTv, Ui.margins(Ui.lp(-2, -2), dp(14), 0, 0, dp(8)));
        head.addView(Ui.spacer(c));
        // data (opzione) tra l'autonomia e l'ora, allineata alla base dei numeri
        dateTv = Ui.oneLine(Ui.text(c, "", 26, t.sub, Ui.REGULAR));
        dateTv.setVisibility(prefs.showDate() ? VISIBLE : GONE);
        head.addView(dateTv, Ui.margins(Ui.lp(-2, -2), dp(12), 0, dp(22), dp(8)));
        // orologio, grande quanto l'autonomia
        clockTv = Ui.text(c, "", 76, t.text, Ui.LIGHT);
        clockTv.setLetterSpacing(-0.03f);
        head.addView(clockTv);
        // spia luci del sistema in alto a sinistra: l'autonomia parte più a destra
        if (sysIndicator && !rhd) head.setPadding(dp(SYSTEM_LIGHT_INSET), 0, 0, 0);
        p.addView(head);

        LinearLayout batRow = Ui.row(c);
        batRow.setGravity(Gravity.CENTER_VERTICAL);
        battery = new Bars.Progress(c);
        battery.setColors(t.track, t.battery);
        batRow.addView(battery, Ui.lpWeight(0, dp(8), 1));
        socTv = Ui.text(c, "—", 32, t.text, Ui.MEDIUM);   // 0.8.6-beta: più grande (era 24)
        batRow.addView(socTv, Ui.margins(Ui.lp(-2, -2), dp(16), 0, 0, 0));
        p.addView(batRow, Ui.margins(new LayoutParams(-1, -2), 0, dp(10), 0, 0));
        updateClock();

        LinearLayout status = Ui.row(c);
        statusIcon = Ui.icon(c, R.drawable.ic_bolt, t.battery, 22);
        status.addView(statusIcon);
        statusTv = Ui.oneLine(Ui.text(c, L.s(R.string.connecting_vehicle), 20, t.sub, Ui.REGULAR));
        status.addView(statusTv, Ui.margins(Ui.lpWeight(0, -2, 1), dp(10), 0, 0, 0));
        // altezza fissa: a riposo la riga resta vuota senza spostare l'auto
        status.setMinimumHeight(dp(34));
        // temperatura esterna e, sotto, quella interna
        outIcon = Ui.icon(c, R.drawable.ic_thermo, t.text, 26);
        outIcon.setVisibility(GONE);
        status.addView(outIcon);
        LinearLayout temps = Ui.column(c);
        temps.setGravity(Gravity.END);
        outTv = Ui.text(c, "", 26, t.text, Ui.REGULAR);   // 0.8.6-beta: più grande e chiara (era 20, grigia)
        temps.addView(outTv);
        inTv = Ui.text(c, "", 24, t.text, Ui.REGULAR);    // 0.8.6-beta: più grande e chiara (era 17, grigia)
        inTv.setVisibility(showInside() ? VISIBLE : GONE);
        temps.addView(inTv);
        status.addView(temps, Ui.margins(Ui.lp(-2, -2), dp(6), 0, 0, 0));
        status.setGravity(Gravity.TOP);
        p.addView(status, Ui.margins(new LayoutParams(-1, -2), 0, dp(14), 0, 0));

        car = new CarView(c);
        p.addView(car, Ui.margins(Ui.lpWeight(-1, 0, 1), 0, dp(4), 0, dp(4)));

        // pressione pneumatici sotto le ruote dell'auto (anteriori a sinistra, posteriori a destra)
        tpmsRow = Ui.row(c);
        tpmsRow.addView(new View(c), Ui.lpWeight(0, 1, 0.09f));
        tpmsFront = tpmsBlock(c, L.s(R.string.tpms_front));
        tpmsRow.addView(tpmsFront, Ui.lpWeight(0, -2, 0.32f));
        tpmsRow.addView(new View(c), Ui.lpWeight(0, 1, 0.20f));
        tpmsRear = tpmsBlock(c, L.s(R.string.tpms_rear));
        tpmsRow.addView(tpmsRear, Ui.lpWeight(0, -2, 0.32f));
        tpmsRow.addView(new View(c), Ui.lpWeight(0, 1, 0.07f));
        // lo spazio della pressione è sempre riservato (anche prima che arrivi il dato): l'auto non cambia dimensione
        tpmsRow.setVisibility(prefs.showTpms() ? INVISIBLE : GONE);
        p.addView(tpmsRow, Ui.margins(new LayoutParams(-1, -2), 0, 0, 0, dp(10)));

        // pulsanti di ricarica (opzione): limite, corrente AC, avvia/ferma; spenti = più spazio all'auto
        if (prefs.chargeButtons()) {
            chargeRow = Ui.row(c);
            limitBtn = chargeButton(c, chargeRow, L.s(R.string.cb_limit), true, v -> pickLimit());
            limitVal = (TextView) ((ViewGroup) limitBtn).getChildAt(1);
            currentBtn = chargeButton(c, chargeRow, L.s(R.string.cb_current), true, v -> pickCurrent());
            currentVal = (TextView) ((ViewGroup) currentBtn).getChildAt(1);
            startBtn = chargeButton(c, chargeRow, L.s(R.string.cb_charge), false, v -> startStop());
            startLabel = (TextView) ((ViewGroup) startBtn).getChildAt(0);
            startVal = (TextView) ((ViewGroup) startBtn).getChildAt(1);
            p.addView(chargeRow, new LayoutParams(-1, -2));
            styleChargeButtons(new VehicleClient.State());
        }
        return p;
    }

    /** Pulsante di ricarica: titolo piccolo + valore; tocca per cambiare. */
    private View chargeButton(Context c, LinearLayout parent, String label, boolean gap, View.OnClickListener l) {
        LinearLayout ch = Ui.column(c);
        ch.setBackground(Ui.pressable(c, t.chip, 14, t));
        ch.setPadding(dp(16), dp(12), dp(16), dp(12));
        ch.setMinimumHeight(tp(72));
        ch.setGravity(Gravity.CENTER_VERTICAL);
        ch.addView(Ui.oneLine(Ui.text(c, label, 14, t.sub, Ui.REGULAR)));
        TextView v = Ui.oneLine(Ui.text(c, "—", ts(20), t.text, Ui.MEDIUM));
        ch.addView(v, Ui.margins(new LayoutParams(-2, -2), 0, dp(4), 0, 0));
        ch.setClickable(true);
        ch.setContentDescription(label);
        ch.setOnClickListener(l);
        parent.addView(ch, Ui.margins(Ui.lpWeight(0, -2, 1), 0, 0, gap ? dp(10) : 0, 0));
        return ch;
    }

    private void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setClickable(on);
        v.setAlpha(on ? 1f : 0.45f);
    }

    private static final String[] LIMIT_LABELS = {"40%", "50%", "60%", "70%", "80%", "90%", "100%"};

    private void styleChargeButtons(VehicleClient.State s) {
        if (chargeRow == null) return;
        boolean ok = s.connected;
        limitVal.setText(s.chargeLimitCode >= 1 ? LIMIT_LABELS[s.chargeLimitCode - 1] : "—");
        enable(limitBtn, ok && s.chargeLimitCode >= 1);
        currentVal.setText(s.currentLimit >= 1 ? VehicleClient.CURRENT_LABELS[s.currentLimit - 1] : "—");
        enable(currentBtn, ok && s.currentLimit >= 1);
        int mode = VehicleClient.plugMode(s);
        boolean v2l = mode == 2;
        int ctrl = v2l ? s.dischargeControl : s.chargeControl;
        startLabel.setText(L.s(v2l ? R.string.cb_v2l : R.string.cb_charge));
        boolean running = v2l ? ctrl == VehicleClient.CTRL_START || VehicleClient.isDischarging(s.chargingStatus)
                : ctrl == VehicleClient.CTRL_START;
        boolean preparing = ctrl == VehicleClient.CTRL_PREPARING;
        String val;
        if (mode == 0) val = L.s(R.string.cb_unplugged);
        else if (preparing) val = L.s(R.string.cb_preparing);
        else val = L.s(running ? R.string.cb_stop : R.string.cb_start);
        startVal.setText(val);
        // come l'originale: comando possibile solo con la batteria pronta e il cavo/la presa collegati
        boolean can = ok && mode != 0 && !preparing && s.bmsAvailable;
        enable(startBtn, can);
        startBtn.setBackground(Ui.pressable(getContext(), can && running ? t.text : t.chip, 14, t));
        startLabel.setTextColor(can && running ? t.panel : t.sub);
        startVal.setTextColor(can && running ? t.panel : t.text);
    }

    private void pickLimit() {
        VehicleClient.State s = lastVehicle;
        Confirm.pick(host.overlay(), t, L.s(R.string.cb_limit_title), L.s(R.string.cb_limit_msg), LIMIT_LABELS,
                s.chargeLimitCode - 1, L.s(R.string.btn_cancel), i -> vehicle.setChargeLimitCode(i + 1));
    }

    private void pickCurrent() {
        VehicleClient.State s = lastVehicle;
        Confirm.pick(host.overlay(), t, L.s(R.string.cb_current_title), L.s(R.string.cb_current_msg), VehicleClient.CURRENT_LABELS,
                s.currentLimit - 1, L.s(R.string.btn_cancel), i -> vehicle.setCurrentLimit(i + 1));
    }

    private void startStop() {
        VehicleClient.State s = lastVehicle;
        int mode = VehicleClient.plugMode(s);
        if (mode == 0) return;
        boolean v2l = mode == 2;
        int ctrl = v2l ? s.dischargeControl : s.chargeControl;
        boolean running = v2l ? ctrl == VehicleClient.CTRL_START || VehicleClient.isDischarging(s.chargingStatus)
                : ctrl == VehicleClient.CTRL_START;
        if (v2l && !running && s.dischargeLimit > 0 && s.soc >= 0 && s.dischargeLimit > s.soc) {
            // come l'originale: il limite di scarica è sopra la batteria attuale, il V2L non partirebbe
            Confirm.show(host.overlay(), t, L.s(R.string.cb_v2l_title), L.f(R.string.cb_v2l_low, s.dischargeLimit + "%"),
                    L.s(R.string.btn_cancel), L.s(R.string.cb_open_energy), () -> host.openEnergy("DischargeManagementFragment"));
            return;
        }
        int title = v2l ? (running ? R.string.cb_v2l_stop_title : R.string.cb_v2l_start_title)
                : (running ? R.string.cb_charge_stop_title : R.string.cb_charge_start_title);
        int msg = v2l ? (running ? R.string.cb_v2l_stop_msg : R.string.cb_v2l_start_msg)
                : (running ? R.string.cb_charge_stop_msg : R.string.cb_charge_start_msg);
        Confirm.show(host.overlay(), t, L.s(title), L.s(msg), L.s(R.string.btn_cancel), L.s(running ? R.string.cb_stop : R.string.cb_start), () -> {
            if (v2l) vehicle.setDischarging(!running);
            else vehicle.setCharging(!running);
        });
    }

    /** 0.9.5: temperatura interna tolta, l'auto non la manda allo schermo (vedi Impostazioni › Home). */
    private boolean showInside() {
        return false;
    }

    private final long createdAt = System.currentTimeMillis();

    public void onVehicle(VehicleClient.State s) {
        if (!s.connected) {
            boolean early = System.currentTimeMillis() - createdAt < 12000;
            statusTv.setText(L.s(early ? R.string.connecting_vehicle : R.string.vehicle_unavailable));
            statusIcon.setImageResource(R.drawable.ic_info);
            statusIcon.setColorFilter(t.sub);
            return;
        }
        lastVehicle = s;
        String km = s.rangeKm > 0 && s.rangeKm < 2000 ? String.valueOf(s.rangeKm) : "—";
        String unit = s.distUnit == 1 ? "mi" : "km";
        boolean socOk = s.soc >= 0 && s.soc <= 100;
        String soc = prefs.socDecimals() ? String.format(L.locale(), "%.1f", s.soc) : String.valueOf(Math.round(s.soc));
        if (prefs.socBig()) {
            // 0.9: percentuale in grande, autonomia accanto alla barra
            if (socOk) rangeTv.setText(soc);
            rangeUnitTv.setText("%");
            socTv.setText("—".equals(km) ? "—" : km + " " + unit);
        } else {
            rangeTv.setText(km);
            rangeUnitTv.setText(unit);
            if (socOk) socTv.setText(soc + "%");
        }
        if (socOk) battery.setValue(s.soc / 100f);
        battery.setLimit(s.chargeLimit > 0 && s.chargeLimit < 100 ? s.chargeLimit / 100f : -1);
        // stato solo durante ricarica o scarica (tipo, potenza, tempo rimanente); a riposo lo spazio resta vuoto
        String st = chargeText(s);
        statusTv.setText(st);
        statusIcon.setVisibility(st.isEmpty() ? INVISIBLE : VISIBLE);
        statusIcon.setImageResource(VehicleClient.isDischarging(s.chargingStatus) ? R.drawable.ic_plug : R.drawable.ic_bolt);
        statusIcon.setColorFilter(t.battery);
        styleChargeButtons(s);
        if (s.acTemp > 0 && SystemClock.uptimeMillis() > localTempUntil) {
            acTemp = s.acTemp;
            tempTv.setText(tempLabel(acTemp));
        }
        boolean outOk = s.outsideTemp > -60 && s.outsideTemp < 70;
        outIcon.setVisibility(outOk ? VISIBLE : GONE);
        outTv.setText(outOk ? L.f(R.string.outside_temp, String.valueOf(Math.round(s.outsideTemp))) : "");
        // temperatura interna (opzione): "—°" se l'auto non fornisce il dato, mai 0°
        boolean inShow = showInside();
        boolean inOk = !Float.isNaN(s.insideTemp) && Math.round(s.insideTemp) != 0;
        inTv.setVisibility(inShow ? VISIBLE : GONE);
        if (inShow) inTv.setText(L.f(R.string.inside_temp, inOk ? String.valueOf(Math.round(s.insideTemp)) : "—"));
        if (inShow && !outOk) outIcon.setVisibility(VISIBLE);
        showTpms(s.tireBar, s.distUnit);
        if (s.fanLevel >= 0 && SystemClock.uptimeMillis() > localFanUntil) {
            fanLevel = s.fanLevel;
            fanTv.setText(fanLabel(fanLevel));
        }
        fanMin = s.fanMin;
        fanMax = s.fanMax;
        styleToggle(acBtn, s.acOn);
        styleToggle(autoBtn, s.autoOn);
        styleHeat(seatLeftBtn, s.seatLeft);
        styleHeat(seatRightBtn, s.seatRight);
        styleToggle(wheelBtn, s.wheelHeat, 0xFFFF7A3D);
        frontFogBtn.setVisibility(s.hasFrontFog ? VISIBLE : GONE);
        // ricircolo: si nasconde se l'auto non lo supporta (lettura -1)
        recircBtn.setVisibility(s.loopMode >= 0 ? VISIBLE : GONE);
        styleToggle(recircBtn, s.loopMode < 0 ? -1 : s.loopMode == 0 ? 1 : 0);
        setRecircIcon(s.loopMode);
        styleToggle(frontFogBtn, s.frontFog, 0xFF34C77B);
        styleToggle(rearFogBtn, s.rearFog, 0xFFFFB020);
        if (grouped) styleGroups(s);
        styleGuida(s);
        if (popup != null) popup.refresh(s);
    }

    /** Testo di stato: solo durante ricarica o scarica (tipo · potenza · tempo rimanente), altrimenti vuoto. */
    private String chargeText(VehicleClient.State s) {
        String kw = s.chargeKw > 0.3f ? String.format(L.locale(), " · %.1f kW", s.chargeKw) : "";
        switch (s.chargingStatus) {
            case 1: return L.s(R.string.charge_charging) + kw + remaining(s.predictMinutes);
            case 3: return L.s(R.string.charge_balancing) + kw + remaining(s.predictMinutes);
            case 10: return L.s(R.string.charge_fast) + kw + remaining(s.predictMinutes);
            case 11: return s.chargeKw > 0.3f ? L.s(R.string.charge_fast) + kw + remaining(s.predictMinutes) : "";
            case 16: return L.s(R.string.charge_wireless) + kw + remaining(s.predictMinutes);
            case 13: return L.s(R.string.v2l_active) + kw + remaining(s.dischargeMinutes);
            default: return "";
        }
    }

    /** " · 1 h 25 min" (tempo rimanente), vuoto se non disponibile. */
    private static String remaining(int minutes) {
        if (minutes <= 0 || minutes >= 24 * 60) return "";
        int h = minutes / 60, m = minutes % 60;
        return " · " + (h > 0 ? L.f(R.string.time_h_min, h, m) : L.f(R.string.time_min, m));
    }

    /** Aggiorna l'orologio (chiamato ogni minuto dall'activity). */
    public void updateClock() {
        if (clockTv == null) return;
        boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
        Date now = new Date();
        clockTv.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", L.locale()).format(now));
        if (dateTv != null && dateTv.getVisibility() == VISIBLE) dateTv.setText(shortDate(now));
    }

    /** "Ven 16 ott" nella lingua scelta (giorno e mese abbreviati, senza punti). */
    public static String shortDate(Date d) {
        Locale l = L.locale();
        String day = new SimpleDateFormat("EEE", l).format(d).replace(".", "");
        String mon = new SimpleDateFormat("LLL", l).format(d).replace(".", "");
        String num = new SimpleDateFormat("d", l).format(d);
        day = day.isEmpty() ? day : day.substring(0, 1).toUpperCase(l) + day.substring(1);
        return day + " " + num + " " + mon.toLowerCase(l);
    }

    /** Indicatori in alto a destra: visibili solo se c'è qualcosa da mostrare. */
    public void onConn(com.mg4.launcher.svc.ConnClient.State s) {
        connBt.setVisibility(s.btOn ? VISIBLE : GONE);
        connBt.setAlpha(s.btConnected ? 1f : 0.4f);
        connWifi.setVisibility(s.wifiConnected ? VISIBLE : GONE);
        connWifi.setAlpha(0.45f + 0.55f * s.wifiLevel / 3f);
        boolean net = s.mobileType >= 0;
        connNet.setVisibility(net ? VISIBLE : GONE);
        connBars.setVisibility(net ? VISIBLE : GONE);
        connNet.setText(s.mobileType >= 2 ? s.mobileType + "G" : "—");
        connBars.setLevel(s.mobileType >= 2 ? s.mobileLevel : 0);
        // margine del primo elemento visibile
        boolean first = true;
        for (View v : new View[]{connBt, connWifi, connNet}) {
            if (v.getVisibility() != VISIBLE) continue;
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            lp.leftMargin = first ? 0 : dp(12);
            v.setLayoutParams(lp);
            first = false;
        }
        connRow.setVisibility(s.btOn || s.wifiConnected || net ? VISIBLE : GONE);
    }

    // =========================================================================================
    // Pannello destro
    // =========================================================================================

    private FrameLayout buildRightPanel(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.round(t.panel, dp(24)));
        f.setClipToOutline(true);

        mapPanel = buildMapPanel(c);
        mediaPanel = buildMediaPanel(c);
        phonePanel = buildPhonePanel(c);
        f.addView(mapPanel, new FrameLayout.LayoutParams(-1, -1));
        f.addView(mediaPanel, new FrameLayout.LayoutParams(-1, -1));
        f.addView(phonePanel, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout tabs = Ui.row(c);
        tabs.setBackground(Ui.round(t.overlay, dp(16)));
        tabs.setPadding(dp(6), dp(6), dp(6), dp(6));
        tabMap = addTab(c, tabs, L.s(R.string.tab_map), TAB_MAP);
        tabMedia = addTab(c, tabs, L.s(R.string.tab_media), TAB_MEDIA);
        tabPhone = addTab(c, tabs, "CarPlay", TAB_PHONE);
        // 0.9.6: un solo tocco apre CarPlay / Android Auto (prima il primo tocco mostrava solo la scheda)
        tabPhone.setOnClickListener(v -> {
            selectTab(TAB_PHONE, true);
            media.openProjection();
        });
        tabPhone.setVisibility(GONE);
        // indicatori Bluetooth / Wi-Fi / 4G a sinistra delle schede
        LinearLayout topRight = Ui.row(c);
        topRight.setGravity(Gravity.CENTER_VERTICAL);
        connRow = Ui.row(c);
        connRow.setGravity(Gravity.CENTER_VERTICAL);
        connRow.setPadding(dp(14), 0, dp(14), 0);
        connRow.setBackground(Ui.round(t.overlay, dp(16)));
        connBt = Ui.icon(c, R.drawable.ic_bluetooth, t.text, 22);
        connRow.addView(connBt, new LayoutParams(dp(22), dp(22)));
        connWifi = Ui.icon(c, R.drawable.ic_wifi, t.text, 24);
        connRow.addView(connWifi, Ui.margins(new LayoutParams(dp(24), dp(24)), dp(12), 0, 0, 0));
        connNet = Ui.text(c, "4G", 14, t.text, Ui.MEDIUM);
        connRow.addView(connNet, Ui.margins(new LayoutParams(-2, -2), dp(12), 0, 0, 0));
        connBars = new Bars.Signal(c);
        connBars.setColors(t.text, t.track);
        connRow.addView(connBars, Ui.margins(new LayoutParams(dp(22), dp(18)), dp(5), 0, 0, dp(2)));
        connRow.setVisibility(GONE);
        topRight.addView(connRow, Ui.margins(new LayoutParams(-2, tp(48) + dp(12)), 0, 0, dp(10), 0));
        topRight.addView(tabs);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(20), dp(20), 0);
        f.addView(topRight, lp);
        return f;
    }

    private TextView addTab(Context c, LinearLayout parent, String label, int which) {
        TextView tv = Ui.text(c, label, ts(18), t.sub, Ui.MEDIUM);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(tp(22), 0, tp(22), 0);
        tv.setMinHeight(tp(48));
        tv.setClickable(true);
        tv.setOnClickListener(v -> selectTab(which, true));
        parent.addView(tv, Ui.margins(Ui.lp(-2, tp(48)), which == 0 ? 0 : dp(6), 0, 0, 0));
        return tv;
    }

    private void selectTab(int which, boolean byUser) {
        tab = which;
        if (byUser) userPickedTab = true;
        mapPanel.setVisibility(which == TAB_MAP ? VISIBLE : GONE);
        mediaPanel.setVisibility(which == TAB_MEDIA ? VISIBLE : GONE);
        phonePanel.setVisibility(which == TAB_PHONE ? VISIBLE : GONE);
        styleTab(tabMap, which == TAB_MAP);
        styleTab(tabMedia, which == TAB_MEDIA);
        styleTab(tabPhone, which == TAB_PHONE);
    }

    private void styleTab(TextView tv, boolean on) {
        tv.setBackground(on ? Ui.round(t.text, dp(12)) : Ui.pressable(getContext(), 0, 12, t));
        tv.setTextColor(on ? t.panel : t.sub);
    }

    // ---- mappa -------------------------------------------------------------------------------

    private View buildMapPanel(Context c) {
        FrameLayout f = new FrameLayout(c);
        Bars.MapDecor decor = new Bars.MapDecor(c);
        decor.setColors(t.map, t.road, t.roadMinor);
        f.addView(decor, new FrameLayout.LayoutParams(-1, -1));

        // velo sfumato da sinistra: il testo resta leggibile sopra la mappa decorativa
        View veil = new View(c);
        veil.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(t.panel, 0xF2), withAlpha(t.panel, 0xC8), withAlpha(t.panel, 0x00)}));
        f.addView(veil, new FrameLayout.LayoutParams(dp(820), -1));

        // nessuna guida: titolo, barra di ricerca, Casa / Lavoro, "Apri con"
        navIdle = Ui.column(c);
        navIdle.setGravity(Gravity.CENTER_VERTICAL);
        navIdle.setPadding(dp(48), dp(30), dp(40), dp(28));

        TextView q = Ui.text(c, L.s(R.string.where_to), 44, t.text, Ui.MEDIUM);
        q.setLetterSpacing(-0.01f);
        navIdle.addView(q);
        navDefaultTv = Ui.oneLine(Ui.text(c, "", 18, t.sub, Ui.REGULAR));
        navIdle.addView(navDefaultTv, Ui.margins(new LayoutParams(-2, -2), 0, dp(6), 0, 0));

        LinearLayout search = Ui.row(c);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(26), 0, dp(12), 0);
        search.setBackground(Ui.pressable(c, t.chip, 38, t));
        search.setClickable(true);
        search.setContentDescription(L.s(R.string.search_destination));
        search.setOnClickListener(v -> host.launchNavigator());
        search.addView(Ui.icon(c, R.drawable.ic_search, t.sub, 28), new LayoutParams(tp(28), tp(28)));
        search.addView(Ui.oneLine(Ui.text(c, L.s(R.string.search_destination), ts(22), t.sub, Ui.REGULAR)),
                Ui.margins(Ui.lpWeight(0, -2, 1), dp(16), 0, dp(12), 0));
        FrameLayout go = new FrameLayout(c);
        go.setBackground(Ui.round(t.accent, tp(26)));
        go.addView(Ui.icon(c, R.drawable.ic_nav, t.onAccent, 26), new FrameLayout.LayoutParams(tp(26), tp(26), Gravity.CENTER));
        search.addView(go, new LayoutParams(tp(52), tp(52)));
        navIdle.addView(search, Ui.margins(new LayoutParams(dp(640), tp(76)), 0, dp(22), 0, 0));

        navPlaces = Ui.row(c);
        navHomeBtn = placeTile(c, R.drawable.ic_home, L.s(R.string.home_place), v -> nav.goHome());
        navOfficeBtn = placeTile(c, R.drawable.ic_work, L.s(R.string.work_place), v -> nav.goOffice());
        navPlaces.addView(navHomeBtn, Ui.margins(new LayoutParams(dp(312), tp(88)), 0, 0, dp(16), 0));
        navPlaces.addView(navOfficeBtn, new LayoutParams(dp(312), tp(88)));
        navIdle.addView(navPlaces, Ui.margins(new LayoutParams(-2, -2), 0, dp(16), 0, 0));

        // apri con: MG / telefono (CarPlay, Android Auto) / navigatori installati (Waze, Google Maps...)
        navWithBox = Ui.column(c);
        LinearLayout wl = Ui.row(c);
        wl.setGravity(Gravity.BOTTOM);
        TextView wt = Ui.text(c, L.s(R.string.open_with).toUpperCase(L.locale()), 13, t.sub, Ui.MEDIUM);
        wt.setLetterSpacing(0.08f);
        wl.addView(wt);
        wl.addView(Ui.oneLine(Ui.text(c, "· " + L.s(R.string.open_with_hint), 13, t.sub, Ui.REGULAR)),
                Ui.margins(new LayoutParams(-2, -2), dp(8), 0, 0, 0));
        navWithBox.addView(wl);
        navWith = Ui.row(c);
        navWithBox.addView(navWith, Ui.margins(new LayoutParams(-2, -2), -dp(5), dp(8), 0, 0));
        navIdle.addView(navWithBox, Ui.margins(new LayoutParams(-2, -2), 0, dp(22), 0, 0));
        buildNavWith(c, false);
        f.addView(navIdle, new FrameLayout.LayoutParams(-1, -1));

        // guida in corso: indicazione in alto a sinistra + arrivo in basso
        navCard = Ui.row(c);
        navCard.setBackground(Ui.round(t.overlay, dp(18)));
        navCard.setPadding(dp(22), dp(16), dp(26), dp(16));
        navCard.addView(Ui.icon(c, R.drawable.ic_turn, t.text, 44));
        LinearLayout txt = Ui.column(c);
        navCardTitle = Ui.oneLine(Ui.text(c, "", 30, t.text, Ui.MEDIUM));
        navCardSub = Ui.oneLine(Ui.text(c, "", 18, t.sub, Ui.REGULAR));
        txt.addView(navCardTitle);
        txt.addView(navCardSub, Ui.margins(new LayoutParams(-2, -2), 0, dp(4), 0, 0));
        navCard.addView(txt, Ui.margins(new LayoutParams(-2, -2), dp(18), 0, 0, 0));
        navCard.setClickable(true);
        navCard.setOnClickListener(v -> openNavigation());
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cl.setMargins(dp(20 + (rhd && sysIndicator ? SYSTEM_LIGHT_INSET : 0)), dp(20), dp(20), 0);
        f.addView(navCard, cl);

        navEta = Ui.row(c);
        navEta.setBackground(Ui.round(t.overlay, dp(16)));
        navEta.setPadding(dp(18), dp(12), dp(18), dp(12));
        navEtaArrive = Ui.text(c, "", 18, t.text, Ui.MEDIUM);
        navEtaMin = Ui.text(c, "", 18, t.text, Ui.MEDIUM);
        navEtaKm = Ui.text(c, "", 18, t.text, Ui.MEDIUM);
        navEta.addView(navEtaArrive);
        navEta.addView(navEtaMin, Ui.margins(new LayoutParams(-2, -2), dp(22), 0, 0, 0));
        navEta.addView(navEtaKm, Ui.margins(new LayoutParams(-2, -2), dp(22), 0, 0, 0));
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        el.setMargins(dp(20), 0, 0, dp(20));
        f.addView(navEta, el);
        navCard.setVisibility(GONE);
        navEta.setVisibility(GONE);

        // non è una mappa in miniatura ma una scorciatoia: lo dice un'etichetta in basso a destra
        f.addView(shortcutHint(c, L.s(R.string.map_shortcut_hint)), hintLp());
        return f;
    }

    /** Etichetta "scorciatoia": icona + testo su sfondo velato. */
    private View shortcutHint(Context c, String text) {
        LinearLayout h = Ui.row(c);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackground(Ui.round(t.overlay, dp(14)));
        h.setPadding(dp(14), dp(8), dp(16), dp(8));
        h.addView(Ui.icon(c, R.drawable.ic_open, t.sub, 18), new LayoutParams(dp(18), dp(18)));
        h.addView(Ui.oneLine(Ui.text(c, text, 15, t.sub, Ui.REGULAR)), Ui.margins(new LayoutParams(-2, -2), dp(10), 0, 0, 0));
        return h;
    }

    private FrameLayout.LayoutParams hintLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(20), dp(20));
        return lp;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Riquadro Casa / Lavoro: icona in un cerchio + nome + "Avvia percorso". */
    private LinearLayout placeTile(Context c, int icon, String label, View.OnClickListener l) {
        LinearLayout tile = Ui.row(c);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(16), 0, dp(18), 0);
        tile.setBackground(Ui.pressable(c, t.chip, 22, t));
        tile.setClickable(true);
        tile.setContentDescription(label);
        tile.setOnClickListener(l);
        FrameLayout ic = new FrameLayout(c);
        ic.setBackground(Ui.round(t.overlay, dp(28)));
        ic.addView(Ui.icon(c, icon, t.text, 26), new FrameLayout.LayoutParams(tp(26), tp(26), Gravity.CENTER));
        tile.addView(ic, new LayoutParams(tp(56), tp(56)));
        LinearLayout tx = Ui.column(c);
        tx.addView(Ui.oneLine(Ui.text(c, label, ts(21), t.text, Ui.MEDIUM)));
        tx.addView(Ui.oneLine(Ui.text(c, L.s(R.string.go_there), ts(15), t.sub, Ui.REGULAR)), Ui.margins(new LayoutParams(-2, -2), 0, dp(2), 0, 0));
        tile.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(16), 0, 0, 0));
        tile.addView(Ui.icon(c, R.drawable.ic_chev_right, t.sub, 22), new LayoutParams(dp(22), dp(22)));
        return tile;
    }

    /** Riga "Apri con": piccoli pulsanti; tieni premuto per renderlo il navigatore predefinito. */
    private void buildNavWith(Context c, boolean phone) {
        navWithPhone = phone;
        navWith.removeAllViews();
        String pref = prefs.navApp();
        addNavChip(c, "MG", Prefs.NAV_MG, pref);
        if (phone) {
            addNavChip(c, media.last().deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay", Prefs.NAV_PHONE, pref);
        }
        String defName = "MG";
        for (com.mg4.launcher.apps.AppCatalog.App a : host.otherNavApps()) {
            addNavChip(c, a.label, a.packageName, pref);
            if (a.packageName.equals(pref)) defName = a.label;
        }
        if (Prefs.NAV_PHONE.equals(pref) || (Prefs.NAV_AUTO.equals(pref) && phone)) {
            defName = media.last().deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay";
        }
        navDefaultTv.setText(L.f(R.string.nav_default, defName));
        navWithBox.setVisibility(navWith.getChildCount() > 1 ? VISIBLE : GONE);
    }

    private void addNavChip(Context c, String label, String value, String pref) {
        boolean def = value.equals(pref) || (Prefs.NAV_AUTO.equals(pref) && value.equals(navWithPhone ? Prefs.NAV_PHONE : Prefs.NAV_MG));
        TextView chip = Ui.text(c, label, ts(16), def ? t.text : t.sub, Ui.MEDIUM);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(tp(18), 0, tp(18), 0);
        chip.setMinHeight(tp(44));
        chip.setBackground(Ui.pressable(c, def ? t.chip : t.overlay, 22, t));
        chip.setClickable(true);
        chip.setOnClickListener(v -> host.launchNavigator(value));
        chip.setOnLongClickListener(v -> {
            prefs.setNavApp(value);
            buildNavWith(getContext(), navWithPhone);
            return true;
        });
        navWith.addView(chip, Ui.margins(Ui.lp(-2, tp(44)), dp(5), 0, dp(5), 0));
        chip.setBackground(Ui.pressable(c, def ? t.text : t.chip, 22, t));
        chip.setTextColor(def ? t.panel : t.text);
    }

    private void openNavigation() {
        MediaClient.State m = media.last();
        if (m.deviceType != 0 && m.tbtActive) {
            media.openProjection();
            media.openRemoteNavigation();
        } else {
            host.launchNavigator();
        }
    }

    public void onNav(NavClient.State n) {
        MediaClient.State m = media.last();
        boolean remote = m.deviceType != 0 && m.tbtActive;
        boolean guiding = n.navigating || remote;
        navHomeBtn.setVisibility(n.connected && n.hasHome ? VISIBLE : GONE);
        navOfficeBtn.setVisibility(n.connected && n.hasOffice ? VISIBLE : GONE);
        navPlaces.setVisibility(n.connected && (n.hasHome || n.hasOffice) ? VISIBLE : GONE);
        navIdle.setVisibility(guiding ? GONE : VISIBLE);
        navCard.setVisibility(guiding ? VISIBLE : GONE);
        navEta.setVisibility(n.navigating && !remote && n.remainSeconds > 0 ? VISIBLE : GONE);
        if (remote) {
            navCardTitle.setText(m.tbtDistance.isEmpty() ? (m.deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay") : m.tbtDistance);
            navCardSub.setText(m.tbtRoad.isEmpty() ? L.s(R.string.nav_phone) : m.tbtRoad);
        } else if (n.navigating) {
            if (n.turnMeters >= 0) {
                navCardTitle.setText(km(n.turnMeters));
                String next = !n.turnText.isEmpty() ? n.turnText : n.road;
                navCardSub.setText(next.isEmpty() ? L.s(R.string.next_maneuver) : next);
            } else {
                navCardTitle.setText(n.road.isEmpty() ? L.s(R.string.navigating) : n.road);
                navCardSub.setText(n.remainMeters > 0 ? L.f(R.string.remaining, km(n.remainMeters)) : L.s(R.string.tap_open_nav));
            }
            if (n.remainSeconds > 0) {
                Date arrive = new Date(System.currentTimeMillis() + n.remainSeconds * 1000L);
                navEtaArrive.setText(L.f(R.string.arrival, new SimpleDateFormat("HH:mm", L.locale()).format(arrive)));
                navEtaMin.setText(Math.max(1, Math.round(n.remainSeconds / 60f)) + " min");
                navEtaKm.setText(n.remainMeters > 0 ? km(n.remainMeters) : "");
            }
        }
        // all'avvio di una guida mostra la mappa, a meno che l'utente non abbia scelto un'altra scheda
        if (guiding && !wasNavigating && (!userPickedTab || tab != TAB_PHONE)) selectTab(TAB_MAP, false);
        wasNavigating = guiding;
    }

    private static String km(int meters) {
        if (meters < 1000) return meters + " m";
        return String.format(L.locale(), meters < 10000 ? "%.1f km" : "%.0f km", meters / 1000f);
    }

    // ---- media -------------------------------------------------------------------------------

    private View buildMediaPanel(Context c) {
        LinearLayout col = Ui.column(c);
        col.setPadding(dp(28), dp(20), dp(20), dp(20));

        // sorgenti: un pulsante per ciascuna (Radio, USB, Bluetooth, Online, + CarPlay/Android Auto se collegato)
        srcRow = Ui.row(c);
        srcRow.setBackground(Ui.round(t.overlay, dp(16)));
        srcRow.setPadding(dp(6), dp(6), dp(6), dp(6));
        // guida a destra con la spia luci: la riga sorgenti parte più a destra
        col.addView(srcRow, Ui.margins(new LayoutParams(-2, tp(48) + dp(12)), rhd && sysIndicator ? dp(SYSTEM_LIGHT_INSET) : 0, 0, 0, 0));

        // corpo: a sinistra copertina + comandi + preferiti, a destra (solo radio) l'elenco delle stazioni trovate
        LinearLayout body = Ui.row(c);
        col.addView(body, Ui.margins(Ui.lpWeight(-1, 0, 1), 0, dp(10), 0, 0));
        LinearLayout left = Ui.column(c);
        body.addView(left, Ui.lpWeight(0, -1, 1));

        LinearLayout main = Ui.row(c);
        main.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(main, Ui.margins(Ui.lpWeight(-1, 0, 1), dp(8), 0, 0, 0));

        FrameLayout art = new FrameLayout(c);
        art.setBackground(Ui.pressable(c, t.chip, 26, t));
        art.setClipToOutline(true);
        art.setClickable(true);
        art.setContentDescription(L.s(R.string.open_source_app));
        art.setOnClickListener(v -> openCurrentSourceApp());
        artIcon = Ui.icon(c, R.drawable.ic_music, t.sub, 84);
        art.addView(artIcon, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));
        artLogo = new ImageView(c);
        artLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artLogo.setPadding(dp(20), dp(20), dp(20), dp(20));
        art.addView(artLogo, new FrameLayout.LayoutParams(-1, -1));
        artRadio = Ui.column(c);
        artRadio.setGravity(Gravity.CENTER);
        artBand = Ui.text(c, "FM", 20, t.sub, Ui.MEDIUM);
        artBand.setLetterSpacing(0.12f);
        artBand.setGravity(Gravity.CENTER);
        artFreq = Ui.oneLine(Ui.text(c, "", 54, t.text, Ui.LIGHT));
        artFreq.setGravity(Gravity.CENTER);
        artUnit = Ui.oneLine(Ui.text(c, "MHz", 19, t.sub, Ui.REGULAR));
        artUnit.setGravity(Gravity.CENTER);
        artRadio.addView(artBand, new LayoutParams(-1, -2));
        artRadio.addView(artFreq, Ui.margins(new LayoutParams(-1, -2), 0, dp(2), 0, dp(2)));
        artRadio.addView(artUnit, new LayoutParams(-1, -2));
        artRadio.setPadding(dp(12), 0, dp(12), 0);
        art.addView(artRadio, new FrameLayout.LayoutParams(-1, -1));
        // con i comandi più grandi la copertina si restringe un po' per lasciare spazio ai pulsanti
        int artSize = dp(Math.round(208 - 110 * (Ui.K - 1)));
        main.addView(art, new LayoutParams(artSize, artSize));

        LinearLayout info = Ui.column(c);
        main.addView(info, Ui.margins(Ui.lpWeight(0, -2, 1), dp(28), 0, 0, 0));

        mMeta = Ui.oneLine(Ui.text(c, "", 17, t.sub, Ui.MEDIUM));
        mMeta.setLetterSpacing(0.06f);
        info.addView(mMeta, new LayoutParams(-1, -2));
        mTitle = Ui.oneLine(Ui.text(c, "", 40, t.text, Ui.MEDIUM));
        info.addView(mTitle, Ui.margins(new LayoutParams(-1, -2), 0, dp(4), 0, 0));
        mSub = Ui.oneLine(Ui.text(c, "", 21, t.sub, Ui.REGULAR));
        info.addView(mSub, Ui.margins(new LayoutParams(-1, -2), 0, dp(4), 0, 0));

        progressRow = Ui.column(c);
        mProgress = new Bars.Progress(c);
        mProgress.setColors(t.track, t.text);
        progressRow.addView(mProgress, new LayoutParams(-1, dp(6)));
        LinearLayout times = Ui.row(c);
        mPos = Ui.text(c, "", 16, t.sub, Ui.REGULAR);
        mDur = Ui.text(c, "", 16, t.sub, Ui.REGULAR);
        times.addView(mPos);
        times.addView(Ui.spacer(c));
        times.addView(mDur);
        progressRow.addView(times, Ui.margins(new LayoutParams(-1, -2), 0, dp(8), 0, 0));
        info.addView(progressRow, Ui.margins(new LayoutParams(-1, -2), 0, dp(16), 0, 0));

        // comandi grandi + scelta della banda (FM / DAB) subito accanto
        LinearLayout ctr = Ui.row(c);
        ctr.setGravity(Gravity.CENTER_VERTICAL);
        ctr.addView(Ui.iconButton(c, t, R.drawable.ic_prev, t.text, t.chip, 64, 28, 32, L.s(R.string.previous), v -> media.skip(false)));
        FrameLayout play = Ui.iconButton(c, t, R.drawable.ic_pause, t.panel, t.text, 84, 36, 42, L.s(R.string.play_pause), v -> media.playPause());
        playIcon = (ImageView) play.getChildAt(0);
        int gap = dp(Ui.K > 1 ? 12 : 16);
        ctr.addView(play, Ui.margins(Ui.lp(tp(84), tp(84)), gap, 0, gap, 0));
        ctr.addView(Ui.iconButton(c, t, R.drawable.ic_next, t.text, t.chip, 64, 28, 32, L.s(R.string.next), v -> media.skip(true)));
        bandRow = Ui.row(c);
        bandRow.setBackground(Ui.round(t.chip, dp(16)));
        bandRow.setPadding(dp(5), dp(5), dp(5), dp(5));
        bandChips = new TextView[2];
        String[] bands = {"FM", "DAB"};
        int[] bandIds = {MediaClient.BAND_FM, MediaClient.BAND_DAB};
        for (int i = 0; i < 2; i++) {
            final int band = bandIds[i];
            TextView b = Ui.text(c, bands[i], ts(18), t.sub, Ui.MEDIUM);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(tp(64));
            b.setPadding(tp(16), 0, tp(16), 0);
            b.setClickable(true);
            b.setContentDescription(L.s(R.string.src_radio) + " " + bands[i]);
            b.setOnClickListener(v -> {
                pendingBand = band;
                pendingBandUntil = SystemClock.uptimeMillis() + 4000;
                styleBands(band);
                media.switchBand(band);
            });
            bandRow.addView(b, Ui.margins(Ui.lp(-2, tp(54)), i == 0 ? 0 : dp(4), 0, 0, 0));
            bandChips[i] = b;
        }
        ctr.addView(bandRow, Ui.margins(Ui.lp(-2, tp(64)), dp(Ui.K > 1 ? 16 : 24), 0, 0, 0));
        info.addView(ctr, Ui.margins(new LayoutParams(-1, -2), 0, dp(20), 0, 0));

        // preferiti radio con i loghi (FM e DAB)
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(OVER_SCROLL_NEVER);
        favRow = Ui.row(c);
        hs.addView(favRow);
        favScroll = hs;
        left.addView(hs, Ui.margins(new LayoutParams(-1, dp(122)), dp(4), dp(10), 0, 0));

        // elenco delle stazioni trovate dalla radio (banda attuale)
        stationPanel = Ui.column(c);
        stationPanel.setBackground(Ui.round(t.overlay, dp(20)));
        stationPanel.setPadding(dp(8), dp(14), dp(8), dp(8));
        LinearLayout sh = Ui.row(c);
        sh.setGravity(Gravity.CENTER_VERTICAL);
        sh.setPadding(dp(12), 0, dp(10), dp(6));
        stationTitle = Ui.text(c, "", 14, t.sub, Ui.MEDIUM);
        stationTitle.setLetterSpacing(0.08f);
        sh.addView(stationTitle, Ui.lpWeight(0, -2, 1));
        stationCount = Ui.text(c, "", 14, t.sub, Ui.REGULAR);
        sh.addView(stationCount);
        stationPanel.addView(sh, new LayoutParams(-1, -2));
        android.widget.ScrollView ss = new android.widget.ScrollView(c);
        ss.setVerticalScrollBarEnabled(false);
        ss.setOverScrollMode(OVER_SCROLL_NEVER);
        stationList = Ui.column(c);
        ss.addView(stationList, new FrameLayout.LayoutParams(-1, -2));
        stationScroll = ss;
        stationPanel.addView(ss, Ui.lpWeight(-1, 0, 1));
        stationEmpty = Ui.column(c);
        stationEmpty.setGravity(Gravity.CENTER);
        stationEmpty.setPadding(dp(18), 0, dp(18), 0);
        TextView et = Ui.text(c, L.s(R.string.stations_empty), 16, t.sub, Ui.REGULAR);
        et.setGravity(Gravity.CENTER);
        stationEmpty.addView(et);
        TextView eb = Ui.text(c, L.s(R.string.open_radio_app), ts(17), t.text, Ui.MEDIUM);
        eb.setGravity(Gravity.CENTER);
        eb.setPadding(tp(22), 0, tp(22), 0);
        eb.setBackground(Ui.pressable(c, t.chip, 14, t));
        eb.setClickable(true);
        eb.setOnClickListener(v -> host.launch("com.saicmotor.hmi.radio", "com.saicmotor.hmi.radio.app.RadioHomeActivity"));
        stationEmpty.addView(eb, Ui.margins(new LayoutParams(-2, tp(52)), 0, dp(16), 0, 0));
        stationPanel.addView(stationEmpty, Ui.lpWeight(-1, 0, 1));
        body.addView(stationPanel, Ui.margins(new LayoutParams(dp(346), -1), dp(18), 0, 0, 0));
        return col;
    }

    private void styleBands(int band) {
        int sel = band == MediaClient.BAND_DAB ? 1 : band == MediaClient.BAND_FM ? 0 : -1;
        for (int i = 0; i < bandChips.length; i++) {
            bandChips[i].setBackground(i == sel ? Ui.round(t.text, dp(12)) : Ui.pressable(getContext(), 0, 12, t));
            bandChips[i].setTextColor(i == sel ? t.panel : t.sub);
        }
    }

    private static String sourceLabel(int src) {
        switch (src) {
            case MediaClient.SRC_RADIO: return L.s(R.string.src_radio);
            case MediaClient.SRC_USB: return "USB";
            case MediaClient.SRC_BT: return "Bluetooth";
            case MediaClient.SRC_ONLINE: return L.s(R.string.src_online);
            case MediaClient.SRC_CARPLAY: return "CarPlay";
            case MediaClient.SRC_AAUTO: return "Android Auto";
            default: return L.s(R.string.media_generic);
        }
    }

    /** Pulsanti delle sorgenti: si ricreano solo se cambia l'elenco, poi si aggiorna l'evidenziazione. */
    private void showSources(MediaClient.State m) {
        Context c = getContext();
        StringBuilder sig = new StringBuilder();
        for (int src : m.sources) sig.append(src).append(',');
        if (!sig.toString().equals(srcSignature)) {
            srcSignature = sig.toString();
            srcRow.removeAllViews();
            boolean compact = m.sources.size() > 4;
            for (int i = 0; i < m.sources.size(); i++) {
                final int src = m.sources.get(i);
                TextView b = Ui.text(c, sourceLabel(src), ts(17), t.sub, Ui.MEDIUM);
                b.setGravity(Gravity.CENTER_VERTICAL);
                b.setPadding(tp(compact ? 14 : 18), 0, tp(compact ? 16 : 22), 0);
                b.setCompoundDrawablePadding(dp(10));
                b.setClickable(true);
                b.setTag(src);
                b.setContentDescription(sourceLabel(src));
                b.setOnClickListener(v -> {
                    pendingSource = src;
                    pendingSourceUntil = SystemClock.uptimeMillis() + 3000;
                    styleSources(src);
                    media.selectSource(src);
                });
                srcRow.addView(b, Ui.margins(Ui.lp(-2, -1), i == 0 ? 0 : dp(4), 0, 0, 0));
            }
        }
        int shown = SystemClock.uptimeMillis() < pendingSourceUntil ? pendingSource : m.source;
        styleSources(shown);
    }

    private void styleSources(int selected) {
        for (int i = 0; i < srcRow.getChildCount(); i++) {
            TextView b = (TextView) srcRow.getChildAt(i);
            int src = (Integer) b.getTag();
            boolean on = src == selected;
            b.setBackground(on ? Ui.round(t.text, dp(12)) : Ui.pressable(getContext(), 0, 12, t));
            b.setTextColor(on ? t.panel : t.sub);
            boolean compact = srcRow.getChildCount() > 4;
            b.setText(compact && !on ? "" : sourceLabel(src));
            b.setCompoundDrawablePadding(compact && !on ? 0 : dp(10));
            android.graphics.drawable.Drawable d = getContext().getDrawable(sourceIcon(src)).mutate();
            d.setTint(on ? t.panel : t.sub);
            d.setBounds(0, 0, tp(22), tp(22));
            b.setCompoundDrawables(d, null, null, null);
        }
    }

    /** Riquadro di una stazione preferita: logo (o iniziali) + nome. */
    private View stationTile(Context c, MediaClient.Station st, boolean current) {
        LinearLayout tile = Ui.column(c);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setClickable(true);
        tile.setContentDescription(st.label());
        tile.setBackground(Ui.pressable(c, 0, 16, t));
        tile.setOnClickListener(v -> media.tune(st));
        FrameLayout box = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(t.chip);
        g.setCornerRadius(dp(18));
        if (current) g.setStroke(dp(2), t.text);
        box.setBackground(g);
        box.setClipToOutline(true);
        if (st.logo != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(st.logo);
            iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView ini = Ui.text(c, initials(st), 18, t.text, Ui.MEDIUM);
            ini.setGravity(Gravity.CENTER);
            box.addView(ini, new FrameLayout.LayoutParams(-1, -1));
        }
        TextView band = Ui.text(c, st.band == MediaClient.BAND_DAB ? "DAB" : "FM", 10, t.sub, Ui.MEDIUM);
        band.setPadding(dp(5), dp(1), dp(5), dp(1));
        band.setBackground(Ui.round(t.panel, dp(6)));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        blp.setMargins(0, 0, dp(5), dp(5));
        box.addView(band, blp);
        tile.addView(box, new LayoutParams(dp(72), dp(72)));
        // sempre nome + frequenza (o DAB+), anche con il logo
        TextView name = Ui.oneLine(Ui.text(c, st.label(), 14, current ? t.text : t.sub, Ui.MEDIUM));
        name.setGravity(Gravity.CENTER);
        tile.addView(name, Ui.margins(new LayoutParams(dp(96), -2), 0, dp(4), 0, 0));
        String freq = st.band == MediaClient.BAND_DAB ? "DAB+" : String.format(L.locale(), "%.1f MHz", st.khz / 1000f);
        TextView fq = Ui.oneLine(Ui.text(c, freq, 12, t.sub, Ui.REGULAR));
        fq.setGravity(Gravity.CENTER);
        tile.addView(fq, new LayoutParams(dp(96), -2));
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        return tile;
    }

    private static String initials(MediaClient.Station st) {
        String n = st.name.trim();
        if (n.isEmpty()) return st.label();
        String[] w = n.split("\\s+");
        if (w.length == 1) return w[0].length() <= 5 ? w[0].toUpperCase(L.locale()) : w[0].substring(0, 4).toUpperCase(L.locale());
        return (w[0].substring(0, 1) + w[1].substring(0, 1)).toUpperCase(L.locale());
    }

    /** Riga dell'elenco stazioni: logo (o iniziali) + nome + frequenza; tocca per sintonizzare. */
    private View stationRow(Context c, MediaClient.Station st, boolean current) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(12), 0);
        row.setBackground(current ? Ui.round(t.chip, dp(14)) : Ui.pressable(c, 0, 14, t));
        row.setClickable(true);
        row.setContentDescription(st.label());
        row.setOnClickListener(v -> media.tune(st));
        FrameLayout box = new FrameLayout(c);
        box.setBackground(Ui.round(current ? t.overlay : t.chip, dp(10)));
        box.setClipToOutline(true);
        if (st.logo != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(st.logo);
            iv.setPadding(dp(4), dp(4), dp(4), dp(4));
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView ini = Ui.text(c, initials(st), ts(13), t.text, Ui.MEDIUM);
            ini.setGravity(Gravity.CENTER);
            box.addView(ini, new FrameLayout.LayoutParams(-1, -1));
        }
        row.addView(box, new LayoutParams(tp(44), tp(44)));
        LinearLayout tx = Ui.column(c);
        String sub = st.band == MediaClient.BAND_DAB ? "DAB+" : String.format(L.locale(), "FM %.1f MHz", st.khz / 1000f);
        // sempre logo (o iniziali) + nome + frequenza o DAB+
        tx.addView(Ui.oneLine(Ui.text(c, st.label(), ts(18), t.text, current ? Ui.MEDIUM : Ui.REGULAR)));
        tx.addView(Ui.oneLine(Ui.text(c, sub, ts(14), t.sub, Ui.REGULAR)));
        row.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(12), 0, 0, 0));
        if (current) row.addView(Ui.icon(c, R.drawable.ic_wave, t.text, 20), new LayoutParams(dp(20), dp(20)));
        return row;
    }

    private void showStations(MediaClient.State m) {
        boolean dab = m.radioBand == MediaClient.BAND_DAB;
        stationTitle.setText(L.f(R.string.stations_title, dab ? "DAB" : "FM").toUpperCase(L.locale()));
        stationCount.setText(m.stations.isEmpty() ? "" : String.valueOf(m.stations.size()));
        StringBuilder sig = new StringBuilder(m.stationKey).append('#').append(m.radioBand);
        for (MediaClient.Station st : m.stations) sig.append('|').append(st.key).append(st.logo != null ? '+' : '-');
        boolean empty = m.stations.isEmpty();
        stationScroll.setVisibility(empty ? GONE : VISIBLE);
        stationEmpty.setVisibility(empty ? VISIBLE : GONE);
        if (sig.toString().equals(stationSignature)) return;
        boolean firstForBand = m.radioBand != stationBand;
        stationBand = m.radioBand;
        stationSignature = sig.toString();
        stationList.removeAllViews();
        Context c = getContext();
        int currentIndex = -1;
        for (int i = 0; i < m.stations.size(); i++) {
            MediaClient.Station st = m.stations.get(i);
            boolean cur = st.key.equals(m.stationKey);
            if (cur) currentIndex = i;
            stationList.addView(stationRow(c, st, cur), Ui.margins(new LayoutParams(-1, tp(62)), 0, 0, 0, dp(2)));
        }
        // porta in vista la stazione in onda
        if (currentIndex >= 0) {
            final int idx = currentIndex;
            stationScroll.post(() -> {
                View v = stationList.getChildAt(idx);
                if (v == null) return;
                int y = v.getTop() - (stationScroll.getHeight() - v.getHeight()) / 2;
                if (firstForBand) stationScroll.scrollTo(0, Math.max(0, y));
                else if (v.getTop() < stationScroll.getScrollY() || v.getBottom() > stationScroll.getScrollY() + stationScroll.getHeight())
                    stationScroll.smoothScrollTo(0, Math.max(0, y));
            });
        }
    }

    private void showFavorites(MediaClient.State m) {
        StringBuilder sig = new StringBuilder(m.stationKey);
        for (MediaClient.Station st : m.favorites) sig.append('|').append(st.key).append(st.logo != null ? '+' : '-');
        if (sig.toString().equals(favSignature)) return;
        favSignature = sig.toString();
        favRow.removeAllViews();
        for (MediaClient.Station st : m.favorites) {
            favRow.addView(stationTile(getContext(), st, st.key.equals(m.stationKey)), Ui.margins(new LayoutParams(-2, -2), 0, 0, dp(8), 0));
        }
    }

    private void openCurrentSourceApp() {
        MediaClient.State m = media.last();
        switch (m.source) {
            case MediaClient.SRC_RADIO:
                host.launch("com.saicmotor.hmi.radio", "com.saicmotor.hmi.radio.app.RadioHomeActivity");
                break;
            case MediaClient.SRC_ONLINE:
                // 0.9: Amazon Music se c'è, altrimenti Spotify
                if (MediaClient.ONLINE_AMAZON.equals(media.onlinePackage())) media.openOnlineApp("tocco sul lettore");
                else host.launch("com.saicmotor.mediacenter", "com.saicmotor.mediacenter.spotify.MainActivity");
                break;
            case MediaClient.SRC_CARPLAY:
            case MediaClient.SRC_AAUTO:
                media.openProjection();
                break;
            default:
                host.launch("com.saicmotor.hmi.music", "com.saicmotor.hmi.music.ui.activity.MusicActivity");
                break;
        }
    }

    private static int sourceIcon(int src) {
        switch (src) {
            case MediaClient.SRC_USB: return R.drawable.ic_usb;
            case MediaClient.SRC_BT: return R.drawable.ic_bluetooth;
            case MediaClient.SRC_ONLINE: return R.drawable.ic_wave;
            case MediaClient.SRC_CARPLAY:
            case MediaClient.SRC_AAUTO: return R.drawable.ic_smartphone;
            default: return R.drawable.ic_radio;
        }
    }

    public void onMedia(MediaClient.State m) {
        boolean radio = m.source == MediaClient.SRC_RADIO;
        showSources(m);
        mTitle.setText(m.title);
        mSub.setText(m.subtitle);
        boolean logo = radio && m.logo != null;
        artLogo.setVisibility(logo ? VISIBLE : GONE);
        if (logo) artLogo.setImageBitmap(m.logo);
        artRadio.setVisibility(radio && !logo ? VISIBLE : GONE);
        artIcon.setVisibility(radio ? GONE : VISIBLE);
        artIcon.setImageResource(sourceIcon(m.source));
        if (radio) {
            boolean dab = m.radioBand == MediaClient.BAND_DAB;
            String band = dab ? "DAB+" : m.radioBand == MediaClient.BAND_AM ? "AM" : "FM";
            artBand.setText(band);
            artFreq.setText(dab ? m.title : m.radioFreq);
            artFreq.setTextSize(dab ? 30 : 56);
            artUnit.setText(m.radioUnit);
            mMeta.setText(dab ? band + (m.radioUnit.isEmpty() ? "" : " · " + m.radioUnit)
                    : band + (m.radioFreq.isEmpty() ? "" : " · " + m.radioFreq + " " + m.radioUnit));
            styleBands(SystemClock.uptimeMillis() < pendingBandUntil ? pendingBand : m.radioBand);
            showFavorites(m);
        } else {
            mMeta.setText(sourceLabel(m.source).toUpperCase(L.locale()));
        }
        bandRow.setVisibility(radio ? VISIBLE : GONE);
        stationPanel.setVisibility(radio ? VISIBLE : GONE);
        if (radio) showStations(m);
        favScroll.setVisibility(radio && !m.favorites.isEmpty() ? VISIBLE : GONE);
        boolean hasProgress = !radio && m.durationMs > 0;
        progressRow.setVisibility(hasProgress ? VISIBLE : GONE);
        if (hasProgress) {
            long pos = Math.max(0, m.positionMs);
            mProgress.setValue(m.positionMs >= 0 ? pos / (float) m.durationMs : 0);
            mPos.setText(m.positionMs >= 0 ? time(pos) : "");
            mDur.setText(time(m.durationMs));
        }
        playIcon.setImageResource(m.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        miniPlayIcon.setImageResource(m.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        miniTitle.setText(m.title);
        miniSub.setText(sourceLabel(m.source) + (m.subtitle.isEmpty() ? "" : " · " + m.subtitle));
        if (radio && m.logo != null) {
            miniArt.clearColorFilter();
            miniArt.setImageBitmap(m.logo);
        } else {
            miniArt.setColorFilter(t.sub, android.graphics.PorterDuff.Mode.SRC_IN);
            miniArt.setImageResource(sourceIcon(m.source));
        }

        // CarPlay / Android Auto: la scheda compare solo con il telefono collegato
        boolean phone = m.deviceType != 0;
        if (phone != navWithPhone) buildNavWith(getContext(), phone);
        tabPhone.setVisibility(phone ? VISIBLE : GONE);
        if (projBtn != null) {
            projBtn.setVisibility(phone ? VISIBLE : GONE);
            projBtn.setContentDescription(m.deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay");
        }
        tabPhone.setText(m.deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay");
        if (!phone && tab == TAB_PHONE) selectTab(TAB_MEDIA, false);
        if (phone) {
            phoneTitle.setText(m.deviceName.isEmpty() ? L.s(R.string.phone_connected) : m.deviceName);
            phoneSub.setText(L.f(R.string.projection_ready, m.deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay"));
        }
        boolean tbt = phone && m.tbtActive;
        phoneNav.setVisibility(tbt ? VISIBLE : GONE);
        if (tbt) {
            phoneNavDist.setText(m.tbtDistance.isEmpty() ? L.s(R.string.next_maneuver) : m.tbtDistance);
            phoneNavRoad.setText(m.tbtRoad.isEmpty() ? L.s(R.string.nav_phone) : m.tbtRoad);
        }
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(L.locale(), "%02d", s % 60);
    }

    // ---- CarPlay / Android Auto --------------------------------------------------------------

    private View buildPhonePanel(Context c) {
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(40), dp(40), dp(40), dp(40));
        col.addView(Ui.icon(c, R.drawable.ic_smartphone, t.sub, 84));
        phoneTitle = Ui.text(c, L.s(R.string.phone_connected), 30, t.text, Ui.MEDIUM);
        phoneTitle.setGravity(Gravity.CENTER);
        col.addView(phoneTitle, Ui.margins(new LayoutParams(-2, -2), 0, dp(18), 0, 0));
        phoneSub = Ui.text(c, "", 20, t.sub, Ui.REGULAR);
        phoneSub.setGravity(Gravity.CENTER);
        col.addView(phoneSub, Ui.margins(new LayoutParams(-2, -2), 0, dp(12), 0, 0));
        TextView open = Ui.text(c, L.s(R.string.open), ts(20), t.onAccent, Ui.MEDIUM);
        open.setGravity(Gravity.CENTER);
        open.setPadding(tp(40), 0, tp(40), 0);
        open.setMinHeight(tp(60));
        open.setBackground(Ui.pressable(c, t.accent, 30 * Ui.K, t));
        open.setClickable(true);
        open.setOnClickListener(v -> media.openProjection());
        col.addView(open, Ui.margins(new LayoutParams(-2, tp(60)), 0, dp(26), 0, 0));

        // navigazione di CarPlay / Android Auto in corso: prossima manovra
        phoneNav = Ui.row(c);
        phoneNav.setGravity(Gravity.CENTER_VERTICAL);
        phoneNav.setBackground(Ui.pressable(c, t.chip, 18, t));
        phoneNav.setPadding(dp(22), dp(14), dp(26), dp(14));
        phoneNav.addView(Ui.icon(c, R.drawable.ic_turn, t.text, 40));
        LinearLayout nt = Ui.column(c);
        phoneNavDist = Ui.oneLine(Ui.text(c, "", 28, t.text, Ui.MEDIUM));
        phoneNavRoad = Ui.oneLine(Ui.text(c, "", 18, t.sub, Ui.REGULAR));
        nt.addView(phoneNavDist);
        nt.addView(phoneNavRoad, Ui.margins(new LayoutParams(-2, -2), 0, dp(4), 0, 0));
        phoneNav.addView(nt, Ui.margins(new LayoutParams(-2, -2), dp(16), 0, 0, 0));
        phoneNav.setClickable(true);
        phoneNav.setOnClickListener(v -> {
            media.openProjection();
            media.openRemoteNavigation();
        });
        phoneNav.setVisibility(GONE);
        col.addView(phoneNav, Ui.margins(new LayoutParams(-2, -2), 0, dp(22), 0, 0));

        FrameLayout f = new FrameLayout(c);
        f.addView(col, new FrameLayout.LayoutParams(-1, -1));
        f.addView(shortcutHint(c, L.s(R.string.phone_shortcut_hint)), hintLp());
        return f;
    }

    // =========================================================================================
    // Barra in basso
    // =========================================================================================

    // misure della barra grande (px, densità 1 sull'auto): barra 150, pulsanti 112, frecce 92, icone 46
    private static final int BIG_BAR = 150, BIG_BTN = 112, BIG_ARROW = 92, BIG_ICON = 46, BIG_SHORTCUT = 96;

    /** Misura di un elemento della barra: normale (cresce con Dimensione comandi) o grande (fissa). */
    private int bs(float normal, float bigPx) {
        return big ? dp(bigPx) : tp(normal);
    }

    private float bt(float normalSp, float bigSp) {
        return big ? bigSp : ts(normalSp);
    }

    /** Pulsante-icona della barra (le misure sono già finali). */
    private FrameLayout barIcon(Context c, int res, int iconColor, int bg, int size, int icon, float radiusDp, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.pressable(c, bg, radiusDp, t));
        f.setContentDescription(desc);
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(iv, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
        f.setLayoutParams(new LayoutParams(size, size));
        f.setClickable(true);
        f.setOnClickListener(l);
        return f;
    }

    private View buildBottomBar(Context c) {
        LinearLayout bar = Ui.row(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(t.panel, dp(24)));
        bar.setPadding(dp(14), 0, dp(14), 0);
        int btn = bs(76, BIG_BTN), arrow = bs(68, BIG_ARROW), arrowIcon = bs(30, BIG_ICON - 8), icon = bs(32, BIG_ICON);
        float rad = big ? 24 : 20 * Ui.K;

        // temperatura: pulsanti grandi, facili da prendere durante la guida
        LinearLayout tempBox = Ui.row(c);
        tempBox.setGravity(Gravity.CENTER_VERTICAL);
        tempBox.setBackground(Ui.round(t.chip, dp(big ? 24 : 20)));
        tempBox.addView(barIcon(c, R.drawable.ic_chev_left, t.text, 0, arrow, arrowIcon, rad, L.s(R.string.temp_down), v -> changeTemp(-1)));
        tempTv = Ui.text(c, "—", bt(40, 54), t.text, Ui.REGULAR);
        tempTv.setGravity(Gravity.CENTER);
        tempBox.addView(tempTv, new LayoutParams(bs(112, 136), -2));
        tempBox.addView(barIcon(c, R.drawable.ic_chev_right, t.text, 0, arrow, arrowIcon, rad, L.s(R.string.temp_up), v -> changeTemp(1)));
        bar.addView(tempBox, new LayoutParams(-2, btn));

        // ventola: ‹ livello ›, tocca l'icona = app Clima, tieni premuto = accendi/spegni il clima
        LinearLayout fanGroup = Ui.row(c);
        fanGroup.setGravity(Gravity.CENTER_VERTICAL);
        fanGroup.setBackground(Ui.round(t.chip, dp(big ? 24 : 20)));
        fanGroup.addView(barIcon(c, R.drawable.ic_chev_left, t.text, 0, arrow, arrowIcon, rad, L.s(R.string.fan_down), v -> changeFan(-1)));
        LinearLayout fanBox = Ui.row(c);
        fanBox.setGravity(Gravity.CENTER);
        ImageView fanIc = Ui.icon(c, R.drawable.ic_fan, t.accent, 30);
        fanBox.addView(fanIc, new LayoutParams(bs(30, 40), bs(30, 40)));
        fanTv = Ui.text(c, "—", bt(34, 46), t.text, Ui.REGULAR);
        fanTv.setGravity(Gravity.CENTER);
        fanBox.addView(fanTv, Ui.margins(new LayoutParams(-2, -2), dp(10), 0, 0, 0));
        fanBox.setMinimumWidth(bs(118, 150));
        fanBox.setClickable(true);
        fanBox.setContentDescription(L.s(R.string.climate));
        fanBox.setBackground(Ui.pressable(c, 0, 16, t));
        fanBox.setOnClickListener(v -> host.launch("com.saicmotor.hmi.hvac", "com.saicmotor.hmi.hvac.HvacActivity"));
        fanBox.setOnLongClickListener(v -> {
            vehicle.setHvacPower(vehicle.last().hvacPower != 1);
            return true;
        });
        fanGroup.addView(fanBox, new LayoutParams(-2, arrow));
        fanGroup.addView(barIcon(c, R.drawable.ic_chev_right, t.text, 0, arrow, arrowIcon, rad, L.s(R.string.fan_up), v -> changeFan(1)));
        bar.addView(fanGroup, Ui.margins(new LayoutParams(-2, btn), dp(10), 0, 0, 0));

        // A/C, AUTO e ricircolo (lo sbrinamento ha il tasto fisico); riscaldamento: sedile sinistro, volante, sedile destro
        acBtn = toggleText(c, "A/C", L.s(R.string.ac_desc), v -> vehicle.toggleAc());
        autoBtn = toggleText(c, "AUTO", L.s(R.string.auto_desc), v -> vehicle.toggleAuto());
        recircBtn = toggleIcon(c, R.drawable.ic_recirc, L.s(R.string.recirc_desc), v -> vehicle.toggleRecirc());
        recircBtn.setVisibility(GONE);   // compare quando l'auto dice di supportarlo
        seatLeftBtn = heatButton(c, R.drawable.ic_seat_heat, false, L.s(R.string.seat_left), v -> vehicle.pressSeatLeft());
        wheelBtn = toggleIcon(c, R.drawable.ic_wheel_heat, L.s(R.string.wheel_heat), v -> vehicle.toggleWheelHeat());
        seatRightBtn = heatButton(c, R.drawable.ic_seat_heat, true, L.s(R.string.seat_right), v -> vehicle.pressSeatRight());
        grouped = prefs.groupedButtons();
        if (grouped) {
            // un pulsante per gruppo: il tocco apre il riquadro sopra la barra, la pressione lunga fa il comando principale
            climaGroup = groupButton(c, true);
            bar.addView(climaGroup, Ui.margins(new LayoutParams(groupWidth(), btn), dp(10), 0, 0, 0));
            comfortGroup = groupButton(c, false);
            comfortGroup.setVisibility(GONE);   // compare quando l'auto dice di avere almeno un comando
            bar.addView(comfortGroup, Ui.margins(new LayoutParams(groupWidth(), btn), dp(8), 0, 0, 0));
        } else {
            bar.addView(acBtn, Ui.margins(new LayoutParams(btn, btn), dp(10), 0, 0, 0));
            bar.addView(autoBtn, Ui.margins(new LayoutParams(btn, btn), dp(8), 0, 0, 0));
            bar.addView(recircBtn, Ui.margins(new LayoutParams(btn, btn), dp(8), 0, 0, 0));
            // in un gruppo unico, così con la guida a destra il sedile sinistro resta a sinistra
            LinearLayout heat = Ui.row(c);
            heat.addView(seatLeftBtn, new LayoutParams(btn, btn));
            heat.addView(wheelBtn, Ui.margins(new LayoutParams(btn, btn), dp(8), 0, 0, 0));
            heat.addView(seatRightBtn, Ui.margins(new LayoutParams(btn, btn), dp(8), 0, 0, 0));
            bar.addView(heat, Ui.margins(new LayoutParams(-2, -2), dp(8), 0, 0, 0));
        }
        // guida (0.9.5): LKA, OPD, assistente e pedale in un gruppo; nascosto se l'auto non ha nessuno dei comandi
        guidaGroup = guidaButton(c);
        guidaGroup.setVisibility(GONE);
        bar.addView(guidaGroup, Ui.margins(new LayoutParams(groupWidth(), btn), dp(8), 0, 0, 0));
        bar.addView(new View(c), new LayoutParams(dp(8), 1));

        // mini-player compatto (unico elemento elastico). Con i comandi molto grandi o la barra grande lascia il posto ai
        // pulsanti: la musica resta nel pannello Media.
        // con i gruppi c'è spazio anche con la barra grande
        boolean showMini = grouped ? Ui.K < 1.5f : !big && Ui.K < 1.25f;
        if (showMini) bar.addView(Ui.divider(c, t.track, big ? 72 : 48));
        LinearLayout mini = Ui.row(c);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(6), 0, dp(4), 0);
        miniArt = Ui.icon(c, R.drawable.ic_radio, t.sub, 26);
        FrameLayout artBox = new FrameLayout(c);
        artBox.setBackground(Ui.round(t.chip, big ? dp(16) : tp(12)));
        artBox.addView(miniArt, new FrameLayout.LayoutParams(bs(26, 40), bs(26, 40), Gravity.CENTER));
        mini.addView(artBox, new LayoutParams(bs(48, 76), bs(48, 76)));
        LinearLayout mt = Ui.column(c);
        miniTitle = Ui.oneLine(Ui.text(c, "", bt(17, 22), t.text, Ui.MEDIUM));
        miniSub = Ui.oneLine(Ui.text(c, "", bt(14, 18), t.sub, Ui.REGULAR));
        mt.addView(miniTitle);
        mt.addView(miniSub, Ui.margins(new LayoutParams(-2, -2), 0, dp(2), 0, 0));
        mini.addView(mt, Ui.margins(Ui.lpWeight(0, -2, 1), dp(12), 0, dp(4), 0));
        mini.setClickable(true);
        mini.setOnClickListener(v -> selectTab(TAB_MEDIA, true));
        FrameLayout mp = Ui.iconButton(c, t, R.drawable.ic_pause, t.text, 0, big ? 76 : 52, big ? 36 : 26, 26, L.s(R.string.play_pause), v -> media.playPause());
        miniPlayIcon = (ImageView) mp.getChildAt(0);
        mini.addView(mp);
        mini.addView(Ui.iconButton(c, t, R.drawable.ic_next, t.text, 0, big ? 76 : 52, big ? 34 : 24, 26, L.s(R.string.next), v -> media.skip(true)));
        if (showMini) bar.addView(mini, Ui.lpWeight(0, -1, 1));
        else bar.addView(Ui.spacer(c));

        bar.addView(Ui.divider(c, t.track, big ? 72 : 48));

        // fendinebbia (sulla MG4 si accendono solo dallo schermo); il retronebbia si può nascondere se l'auto ha il tasto
        int fog = bs(68, BIG_BTN);
        frontFogBtn = toggleIcon(c, R.drawable.ic_fog_front, L.s(R.string.fog_front), v -> vehicle.toggleFrontFog());
        frontFogBtn.setVisibility(GONE);
        bar.addView(frontFogBtn, Ui.margins(new LayoutParams(fog, fog), 0, 0, dp(8), 0));
        rearFogBtn = toggleIcon(c, R.drawable.ic_fog_rear, L.s(R.string.fog_rear), v -> vehicle.toggleRearFog());
        boolean rearFog = prefs.showRearFog();
        if (!rearFog) rearFogBtn.setVisibility(GONE);
        bar.addView(rearFogBtn, new LayoutParams(fog, fog));

        bar.addView(Ui.divider(c, t.track, big ? 72 : 48));

        // scorciatoie; con la barra grande restano telefono e 360° (Radio è nel pannello Media, le Impostazioni tra le app)
        // 0.9.6: CarPlay / Android Auto nella barra, solo con il telefono collegato
        int pj = bs(64, BIG_SHORTCUT);
        projBtn = barIcon(c, R.drawable.ic_projection, t.text, 0, pj, bs(30, BIG_ICON - 2), big ? 22 : 18 * Ui.K, "CarPlay / Android Auto",
                v -> media.openProjection());
        projBtn.setLayoutParams(Ui.margins(Ui.lp(pj, pj), dp(6), 0, 0, 0));
        projBtn.setVisibility(GONE);
        bar.addView(projBtn);
        bar.addView(shortcut(c, R.drawable.ic_phone, L.s(R.string.app_phone), "com.saicmotor.hmi.btcall", "com.saicmotor.hmi.btcall.BtCallActivity"));
        if (!big) bar.addView(shortcut(c, R.drawable.ic_radio, L.s(R.string.src_radio), "com.saicmotor.hmi.radio", "com.saicmotor.hmi.radio.app.RadioHomeActivity"));
        bar.addView(shortcut(c, 0, L.s(R.string.app_camera), "com.saicmotor.hmi.aroundview", "com.saicmotor.hmi.aroundview.AVMActivity"));
        if (!big) bar.addView(shortcut(c, R.drawable.ic_gear, L.s(R.string.app_settings), "com.saicmotor.hmi.systemsettings", "com.saicmotor.hmi.systemsettings.SettingsActivity"));
        // le app si aprono scorrendo la home verso sinistra: niente pulsante dedicato
        if (rhd) mirrorRow(bar);
        barView = bar;
        return bar;
    }

    /**
     * Guida a destra: ordine degli elementi invertito (la temperatura finisce all'estremo destro, vicino al
     * guidatore), mentre dentro ogni elemento tutto resta da sinistra a destra (frecce ‹ › comprese).
     */
    private static void mirrorRow(LinearLayout row) {
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            if (v.getLayoutParams() instanceof MarginLayoutParams) {
                MarginLayoutParams lp = (MarginLayoutParams) v.getLayoutParams();
                lp.setMarginStart(lp.leftMargin);
                lp.setMarginEnd(lp.rightMargin);
                v.setLayoutParams(lp);
            }
        }
    }

    /** Blocco TPMS: titolo piccolo (Anteriori / Posteriori) + "2,5 · 2,5 bar" (sinistra · destra). */
    private LinearLayout tpmsBlock(Context c, String title) {
        LinearLayout b = Ui.column(c);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView tt = Ui.text(c, title.toUpperCase(L.locale()), 12, t.sub, Ui.MEDIUM);
        tt.setLetterSpacing(0.08f);
        b.addView(tt);
        TextView v = Ui.oneLine(Ui.text(c, "— · — bar", 19, t.text, Ui.MEDIUM));
        v.setGravity(Gravity.CENTER);
        b.addView(v, Ui.margins(new LayoutParams(-2, -2), 0, dp(2), 0, 0));
        return b;
    }

    private void showTpms(float[] bar, int distUnit) {
        boolean any = false;
        for (float f : bar) if (!Float.isNaN(f)) any = true;
        // spazio sempre riservato: senza dato la riga è invisibile ma occupa la stessa altezza
        tpmsRow.setVisibility(!prefs.showTpms() ? GONE : any ? VISIBLE : INVISIBLE);
        if (!any) return;
        String unit = tyreUnit(prefs.tyreUnit(), distUnit);
        setTpms(tpmsFront, bar[0], bar[1], unit);
        setTpms(tpmsRear, bar[2], bar[3], unit);
    }

    /** Unità effettiva: scelta nelle impostazioni, oppure "Automatica" = psi se il quadro è in miglia, altrimenti bar. */
    static String tyreUnit(String pref, int distUnit) {
        if (!Prefs.TYRE_AUTO.equals(pref)) return pref;
        return distUnit == 1 ? Prefs.TYRE_PSI : Prefs.TYRE_BAR;
    }

    /** Pressione in bar convertita nell'unità scelta (bar con un decimale, kPa e psi interi). */
    static String tyreValue(float bar, String unit) {
        if (Float.isNaN(bar)) return "—";
        if (Prefs.TYRE_KPA.equals(unit)) return String.valueOf(Math.round(bar * 100f));
        if (Prefs.TYRE_PSI.equals(unit)) return String.valueOf(Math.round(bar * 14.5038f));
        return String.format(L.locale(), "%.1f", bar);
    }

    static String tyreUnitLabel(String unit) {
        return Prefs.TYRE_KPA.equals(unit) ? "kPa" : Prefs.TYRE_PSI.equals(unit) ? "psi" : "bar";
    }

    private void setTpms(LinearLayout block, float left, float right, String unit) {
        TextView v = (TextView) block.getChildAt(1);
        v.setText(tyreValue(left, unit) + " · " + tyreValue(right, unit) + " " + tyreUnitLabel(unit));
        // pressione bassa (sotto 2,0 bar): in ambra
        boolean low = (!Float.isNaN(left) && left < 2.0f) || (!Float.isNaN(right) && right < 2.0f);
        v.setTextColor(low ? 0xFFFFB020 : t.text);
    }

    /** Pulsante sedile riscaldato: icona (specchiata per il lato destro) + tre tacche per il livello. */
    private FrameLayout heatButton(Context c, int icon, boolean mirror, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        f.setContentDescription(desc);
        f.setClickable(true);
        f.setOnClickListener(l);
        ImageView iv = Ui.icon(c, icon, t.text, 32);
        if (mirror) iv.setScaleX(-1f);
        f.addView(iv, new FrameLayout.LayoutParams(bs(32, BIG_ICON), bs(32, BIG_ICON), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        ((FrameLayout.LayoutParams) iv.getLayoutParams()).topMargin = bs(14, 20);
        LinearLayout dots = Ui.row(c);
        for (int i = 0; i < 3; i++) {
            View d = new View(c);
            d.setBackground(Ui.round(t.track, dp(3)));
            dots.addView(d, Ui.margins(new LayoutParams(bs(12, 18), bs(5, 7)), i == 0 ? 0 : bs(4, 6), 0, 0, 0));
        }
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dl.bottomMargin = bs(14, 20);
        f.addView(dots, dl);
        styleHeat(f, -1);
        return f;
    }

    /** Livello 0..3: tacche arancioni accese, icona arancione se acceso; -1 = non disponibile (attenuato). */
    private void styleHeat(FrameLayout f, int level) {
        Integer prev = (Integer) f.getTag();
        if (prev != null && prev == level) return;
        f.setTag(level);
        f.setAlpha(level < 0 ? 0.45f : 1f);
        f.setBackground(Ui.pressable(getContext(), t.chip, big ? 24 : 20 * Ui.K, t));
        int heat = 0xFFFF7A3D;
        ((ImageView) f.getChildAt(0)).setColorFilter(level > 0 ? heat : t.text);
        LinearLayout dots = (LinearLayout) f.getChildAt(1);
        for (int i = 0; i < 3; i++) dots.getChildAt(i).setBackground(Ui.round(i < level ? heat : t.track, dp(3)));
    }

    /** Pulsante a commutazione con testo (A/C, AUTO): acceso = colori invertiti. */
    private TextView toggleText(Context c, String label, String desc, View.OnClickListener l) {
        TextView b = Ui.text(c, label, label.length() > 3 ? bt(17, 24) : bt(20, 28), t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(desc);
        b.setClickable(true);
        b.setOnClickListener(l);
        styleToggle(b, -1);
        return b;
    }

    private FrameLayout toggleIcon(Context c, int icon, String desc, View.OnClickListener l) {
        FrameLayout f = barIcon(c, icon, t.text, t.chip, bs(76, BIG_BTN), bs(32, BIG_ICON), big ? 24 : 20 * Ui.K, desc, l);
        styleToggle(f, -1);
        return f;
    }

    /** state: 1 acceso, 0 spento, -1 sconosciuto/non disponibile (attenuato). onColor = colore dell'icona da acceso. */
    private void styleToggle(View v, int state) {
        styleToggle(v, state, 0);
    }

    private void styleToggle(View v, int state, int onColor) {
        boolean on = state == 1;
        Integer prev = (Integer) v.getTag();
        if (prev != null && prev == state) return;
        v.setTag(state);
        v.setAlpha(state < 0 ? 0.45f : 1f);
        float rad = big ? 24 : 20 * Ui.K;
        if (onColor != 0) {
            // fendinebbia: icona colorata come la spia del quadro (verde anteriori, ambra posteriori)
            v.setBackground(Ui.pressable(getContext(), t.chip, rad, t));
            ((ImageView) ((FrameLayout) v).getChildAt(0)).setColorFilter(on ? onColor : t.text);
            return;
        }
        v.setBackground(on ? Ui.round(t.text, dp(rad)) : Ui.pressable(getContext(), t.chip, rad, t));
        if (v instanceof TextView) ((TextView) v).setTextColor(on ? t.panel : t.text);
        else ((ImageView) ((FrameLayout) v).getChildAt(0)).setColorFilter(on ? t.panel : t.text);
    }

    /**
     * Scorciatoia a un'app originale. Se l'attività esatta non c'è si apre l'app principale; se manca anche quella il
     * pulsante resta attenuato e non fa nulla. icon = 0: scritta "360°" al posto dell'icona.
     */
    private View shortcut(Context c, int icon, String desc, String pkg, String cls) {
        int size = bs(64, BIG_SHORTCUT);
        View.OnClickListener l = v -> host.launch(pkg, cls);
        View f;
        if (icon == 0) {
            TextView tv = Ui.text(c, "360°", bt(22, 30), t.text, Ui.MEDIUM);
            tv.setGravity(Gravity.CENTER);
            tv.setLetterSpacing(-0.02f);
            tv.setBackground(Ui.pressable(c, 0, big ? 22 : 18 * Ui.K, t));
            tv.setContentDescription(desc);
            tv.setClickable(true);
            tv.setOnClickListener(l);
            f = tv;
        } else {
            f = barIcon(c, icon, t.text, 0, size, bs(30, BIG_ICON - 2), big ? 22 : 18 * Ui.K, desc, l);
        }
        f.setLayoutParams(Ui.margins(Ui.lp(size, size), dp(6), 0, 0, 0));
        if (!host.canLaunch(pkg, cls)) {
            f.setAlpha(0.35f);
            f.setClickable(false);
            f.setOnClickListener(null);
        }
        return f;
    }

    // =========================================================================================
    // Gruppi Clima / Comfort (0.8.2): grafica come gli esempi approvati (esempio-barra, -clima-aperto, -comfort-aperto)
    // =========================================================================================

    private static final int HEAT = 0xFFFF7A3D;
    private static final int CLIMA = 0, COMFORT = 1, GUIDA = 2;

    private int groupWidth() {
        return bs(76, BIG_BTN);
    }

    /**
     * Pulsante del gruppo, grande come AUTO: Clima = "A/C" + icona ricircolo, Comfort = sedile + volante (arancioni se
     * accesi). Sotto, un puntino per ogni comando acceso (bianchi per Clima, arancioni per Comfort).
     */
    private FrameLayout groupButton(Context c, boolean clima) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.pressable(c, t.chip, big ? 24 : 20 * Ui.K, t));
        f.setContentDescription(L.s(clima ? R.string.group_clima : R.string.group_comfort));
        f.setClickable(true);
        LinearLayout content = Ui.row(c);
        content.setGravity(Gravity.CENTER);
        int ic = bs(22, 32);
        if (clima) {
            content.addView(Ui.text(c, "A/C", bt(15, 21), t.text, Ui.MEDIUM));
            content.addView(Ui.icon(c, R.drawable.ic_recirc, t.text, 22), Ui.margins(new LayoutParams(ic, ic), bs(5, 7), 0, 0, 0));
        } else {
            content.addView(Ui.icon(c, R.drawable.ic_seat_heat, t.text, 22), new LayoutParams(ic, ic));
            content.addView(Ui.icon(c, R.drawable.ic_wheel_heat, t.text, 22), Ui.margins(new LayoutParams(ic, ic), bs(10, 14), 0, 0, 0));
        }
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        cl.bottomMargin = bs(8, 12);
        f.addView(content, cl);
        LinearLayout dots = Ui.row(c);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View d = new View(c);
            d.setBackground(Ui.round(clima ? t.text : HEAT, dp(3)));
            d.setVisibility(GONE);
            dots.addView(d, Ui.margins(new LayoutParams(bs(6, 9), bs(6, 9)), i == 0 ? 0 : bs(4, 6), 0, 0, 0));
        }
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dl.bottomMargin = bs(9, 14);
        f.addView(dots, dl);
        if (clima) climaDots = dots;
        else comfortDots = dots;
        f.setOnClickListener(v -> openGroup(clima ? CLIMA : COMFORT));
        f.setOnLongClickListener(v -> {
            if (clima) vehicle.toggleAc();
            else if (rhd) vehicle.pressSeatRight();   // pressione lunga: sedile del guidatore
            else vehicle.pressSeatLeft();
            return true;
        });
        return f;
    }

    private static void showDots(LinearLayout dots, int on) {
        for (int i = 0; i < dots.getChildCount(); i++) dots.getChildAt(i).setVisibility(i < on ? VISIBLE : GONE);
    }

    private void styleGroups(VehicleClient.State s) {
        int recirc = s.loopMode < 0 ? -1 : s.loopMode == 0 ? 1 : 0;
        showDots(climaDots, (s.acOn == 1 ? 1 : 0) + (s.autoOn == 1 ? 1 : 0) + (recirc == 1 ? 1 : 0));
        climaGroup.setAlpha(s.acOn < 0 && s.autoOn < 0 ? 0.45f : 1f);
        boolean seatOn = s.seatLeft > 0 || s.seatRight > 0;
        showDots(comfortDots, (s.seatLeft > 0 ? 1 : 0) + (s.wheelHeat > 0 ? 1 : 0) + (s.seatRight > 0 ? 1 : 0));
        LinearLayout icons = (LinearLayout) comfortGroup.getChildAt(0);
        ((ImageView) icons.getChildAt(0)).setColorFilter(seatOn ? HEAT : t.text);
        ((ImageView) icons.getChildAt(1)).setColorFilter(s.wheelHeat > 0 ? HEAT : t.text);
        icons.getChildAt(0).setVisibility(s.seatLeft >= 0 || s.seatRight >= 0 ? VISIBLE : GONE);
        icons.getChildAt(1).setVisibility(s.wheelHeat >= 0 ? VISIBLE : GONE);
        boolean any = s.seatLeft >= 0 || s.seatRight >= 0 || s.wheelHeat >= 0;
        comfortGroup.setVisibility(any ? VISIBLE : GONE);
    }

    /** Icona del ricircolo come l'app clima originale: interno, aria esterna o automatico. */
    static int recircRes(int loopMode) {
        return loopMode == 1 ? R.drawable.ic_recirc_out : loopMode == 2 ? R.drawable.ic_recirc_auto : R.drawable.ic_recirc;
    }

    private void setRecircIcon(int loopMode) {
        int res = recircRes(loopMode);
        if (res == recircIcon) return;
        recircIcon = res;
        ((ImageView) recircBtn.getChildAt(0)).setImageResource(res);
        if (climaGroup != null) {
            LinearLayout content = (LinearLayout) climaGroup.getChildAt(0);
            if (content.getChildCount() > 1) ((ImageView) content.getChildAt(1)).setImageResource(res);
        }
    }

    static String recircName(int loopMode) {
        return loopMode == 0 ? L.s(R.string.recirc_in) : loopMode == 1 ? L.s(R.string.recirc_out) : loopMode == 2 ? "Auto" : "—";
    }

    /** Pulsante del gruppo Guida: icona della strada con le corsie (il volante si confonde col riscaldamento), un puntino per ogni aiuto acceso (LKA, OPD, ACC/TJA). */
    private FrameLayout guidaButton(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.pressable(c, t.chip, big ? 24 : 20 * Ui.K, t));
        f.setContentDescription(L.s(R.string.group_guida));
        f.setClickable(true);
        int ic = bs(28, 40);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER);
        cl.bottomMargin = bs(8, 12);
        f.addView(Ui.icon(c, R.drawable.ic_lanes, t.text, 28), cl);
        guidaDots = Ui.row(c);
        guidaDots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View d = new View(c);
            d.setBackground(Ui.round(t.text, dp(3)));
            d.setVisibility(GONE);
            guidaDots.addView(d, Ui.margins(new LayoutParams(bs(6, 9), bs(6, 9)), i == 0 ? 0 : bs(4, 6), 0, 0, 0));
        }
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dl.bottomMargin = bs(9, 14);
        f.addView(guidaDots, dl);
        f.setOnClickListener(v -> openGroup(GUIDA));
        return f;
    }

    private void styleGuida(VehicleClient.State s) {
        boolean any = s.lka >= 0 || s.opd >= 0 || s.accTja >= 0 || s.pedal >= 0;
        guidaGroup.setVisibility(prefs.showAssist() && any ? VISIBLE : GONE);
        showDots(guidaDots, (s.lka == 1 ? 1 : 0) + (s.opd == 1 ? 1 : 0) + (s.accTja == 1 || s.accTja == 2 ? 1 : 0));
    }

    /** Pedale: Comfort 1 tacca, Medium 2, Sport 3 (valori dell'auto 1, 0, 2). */
    static int pedalNotches(int v) {
        return v == 1 ? 1 : v == 0 ? 2 : v == 2 ? 3 : 0;
    }

    static String pedalName(int v) {
        return v == 1 ? "Comfort" : v == 0 ? "Medium" : v == 2 ? "Sport" : "—";
    }

    static String accName(int v) {
        return v == 1 ? "ACC" : v == 2 ? "TJA" : v == 4 ? "OFF" : "—";
    }

    private void guidaTap(boolean pedal) {
        if (!VehicleClient.stopped()) {
            android.widget.Toast.makeText(getContext(), L.s(R.string.guida_stopped), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (pedal) vehicle.cyclePedal();
        else vehicle.cycleAccTja();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (popup != null) popup.close();
    }

    private void openGroup(int kind) {
        if (popup != null) popup.close();
        popup = new GroupPopup(kind);
        popup.show();
    }

    /** Schermo scurito tranne il pulsante del gruppo aperto, che resta in chiaro con il bordo (bianco o arancione). */
    private final class Dimmer extends FrameLayout {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF hole = new android.graphics.RectF();
        final android.graphics.Path path = new android.graphics.Path();
        int ring;
        float radius;

        Dimmer(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas cv) {
            path.reset();
            path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), android.graphics.Path.Direction.CW);
            if (!hole.isEmpty()) path.addRoundRect(hole, radius, radius, android.graphics.Path.Direction.CW);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(t.day ? 0x80000000 : 0x99000000);
            cv.drawPath(path, p);
            if (!hole.isEmpty()) {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(ring);
                android.graphics.RectF r = new android.graphics.RectF(hole);
                r.inset(-dp(2), -dp(2));
                cv.drawRoundRect(r, radius + dp(2), radius + dp(2), p);
            }
            super.dispatchDraw(cv);
        }
    }

    /**
     * Riquadro sopra la barra con le caselle grandi, come negli esempi: titolo a sinistra, "tocca fuori per chiudere" a
     * destra, nessuna X, punta verso il pulsante. Si chiude toccando fuori o da solo dopo 6 secondi senza tocchi.
     */
    private final class GroupPopup {
        private static final long AUTO_CLOSE_MS = 6000;
        /** clima: caselle bianche quando accese (Clima e Guida); altrimenti arancioni (Comfort). */
        final boolean clima, guida;
        final Dimmer catcher;
        final LinearLayout card, tiles;
        final View arrow;
        final Runnable closer = this::close;
        final java.util.List<FrameLayout> tileViews = new java.util.ArrayList<>();

        GroupPopup(int kind) {
            this.clima = kind != COMFORT;
            this.guida = kind == GUIDA;
            Context c = getContext();
            catcher = new Dimmer(c);
            catcher.ring = clima ? t.text : HEAT;
            catcher.radius = dp(big ? 24 : 20 * Ui.K);
            catcher.setClickable(true);
            catcher.setOnClickListener(v -> close());
            card = Ui.column(c);
            card.setClickable(true);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(t.panel);
            bg.setCornerRadius(dp(28));
            bg.setStroke(dp(1), t.track);
            card.setBackground(bg);
            card.setPadding(dp(32), dp(24), dp(32), dp(30));
            LinearLayout head = Ui.row(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = Ui.text(c, L.s(guida ? R.string.group_guida : clima ? R.string.group_clima : R.string.group_comfort).toUpperCase(L.locale()), 16, t.sub, Ui.MEDIUM);
            title.setLetterSpacing(0.12f);
            head.addView(title, Ui.lpWeight(0, -2, 1));
            head.addView(Ui.text(c, L.s(R.string.tap_outside_close), 15, t.sub, Ui.REGULAR), Ui.margins(new LayoutParams(-2, -2), dp(40), 0, 0, 0));
            card.addView(head, new LayoutParams(-1, -2));
            tiles = Ui.row(c);
            card.addView(tiles, Ui.margins(new LayoutParams(-2, -2), 0, dp(24), 0, 0));
            buildTiles(c);
            arrow = new View(c) {
                final android.graphics.Paint ap = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                final android.graphics.Path tri = new android.graphics.Path();

                @Override
                protected void onDraw(android.graphics.Canvas cv) {
                    tri.reset();
                    tri.moveTo(0, 0);
                    tri.lineTo(getWidth(), 0);
                    tri.lineTo(getWidth() / 2f, getHeight());
                    tri.close();
                    ap.setColor(t.panel);
                    cv.drawPath(tri, ap);
                }
            };
            catcher.addView(card, new FrameLayout.LayoutParams(-2, -2));
            catcher.addView(arrow, new FrameLayout.LayoutParams(dp(28), dp(14)));
            card.setVisibility(INVISIBLE);
            arrow.setVisibility(INVISIBLE);
            card.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> place());
            refresh(vehicle.last());
        }

        private void buildTiles(Context c) {
            VehicleClient.State s = vehicle.last();
            if (guida) {
                if (s.lka >= 0) addTile(c, "lka", "LKA", 0, L.s(R.string.tile_lka), v -> vehicle.toggleLka());
                if (s.opd >= 0) addTile(c, "opd", "OPD", 0, "One Pedal", v -> {
                    VehicleClient.State st = vehicle.last();
                    if (st.opdInhibit > 0 && st.opd == 0) {
                        android.widget.Toast.makeText(getContext(), L.s(R.string.opd_blocked), android.widget.Toast.LENGTH_LONG).show();
                    }
                    vehicle.toggleOpd();
                });
                if (s.accTja >= 0) addTile(c, "acc", accName(s.accTja), 0, L.s(R.string.tile_acc), v -> guidaTap(false));
                if (s.pedal >= 0) addTile(c, "pedal", null, R.drawable.ic_pedal, L.s(R.string.tile_pedal), v -> guidaTap(true));
            } else if (clima) {
                addTile(c, "ac", "A/C", 0, L.s(R.string.ac_desc), v -> vehicle.toggleAc());
                addTile(c, "auto", "AUTO", 0, L.s(R.string.tile_auto), v -> vehicle.toggleAuto());
                if (s.loopMode >= 0) addTile(c, "recirc", null, recircRes(s.loopMode), L.s(R.string.tile_recirc), v -> vehicle.toggleRecirc());
            } else {
                // 0.9.6: caselle nell'ordine fisico dei sedili (sinistro, volante, destro). Con la guida a destra il guidatore
                // è sul sedile destro: cambiano le etichette, non i comandi (ogni casella comanda il sedile che ha davanti).
                // Stesso comando dei pulsanti singoli: 3 → 2 → 1 → spento (prima l'"off" del riquadro non restava).
                if (s.seatLeft >= 0) addTile(c, "seatL", null, R.drawable.ic_seat_heat,
                        L.s(rhd ? R.string.tile_seat_passenger : R.string.tile_seat_driver), v -> vehicle.pressSeatLeft());
                if (s.wheelHeat >= 0) addTile(c, "wheel", null, R.drawable.ic_wheel_heat, L.s(R.string.tile_wheel), v -> vehicle.toggleWheelHeat());
                if (s.seatRight >= 0) addTile(c, "seatR", null, R.drawable.ic_seat_heat,
                        L.s(rhd ? R.string.tile_seat_driver : R.string.tile_seat_passenger), v -> vehicle.pressSeatRight());
            }
        }

        /** Casella: scritta grande o icona, nome, stato sotto (Accesa / Spento / Livello 2) e, per Comfort, le tacche. */
        private void addTile(Context c, String id, String text, int icon, String name, View.OnClickListener l) {
            int w = big ? dp(210) : tp(185), h = big ? dp(256) : tp(228);
            FrameLayout f = new FrameLayout(c);
            f.setContentDescription(name);
            f.setClickable(true);
            f.setOnClickListener(v -> {
                l.onClick(v);
                touched();
            });
            LinearLayout col = Ui.column(c);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            View main;
            int mh = big ? dp(66) : tp(58);
            if (text != null) {
                TextView tv = Ui.text(c, text, big ? 50 : ts(44), t.text, Ui.REGULAR);
                tv.setGravity(Gravity.CENTER);
                main = tv;
                col.addView(tv, new LayoutParams(-2, mh));
            } else {
                ImageView iv = Ui.icon(c, icon, t.text, 48);
                iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                main = iv;
                col.addView(iv, new LayoutParams(mh, mh));
            }
            TextView nm = Ui.oneLine(Ui.text(c, name, big ? 22 : ts(20), t.text, Ui.REGULAR));
            nm.setGravity(Gravity.CENTER);
            col.addView(nm, Ui.margins(new LayoutParams(w - dp(10), -2), 0, dp(10), 0, 0));
            TextView st = Ui.oneLine(Ui.text(c, "", big ? 18 : ts(16), t.sub, Ui.REGULAR));
            st.setGravity(Gravity.CENTER);
            col.addView(st, Ui.margins(new LayoutParams(w - dp(10), -2), 0, dp(4), 0, 0));
            LinearLayout notches = Ui.row(c);
            if (!clima || id.equals("pedal")) {
                int n = id.equals("wheel") ? 1 : 3;
                for (int i = 0; i < n; i++) {
                    View d = new View(c);
                    notches.addView(d, Ui.margins(new LayoutParams(big ? dp(34) : tp(30), big ? dp(7) : tp(6)), i == 0 ? 0 : dp(7), 0, 0, 0));
                }
            }
            col.addView(notches, Ui.margins(new LayoutParams(-2, -2), 0, dp(14), 0, 0));
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
            f.addView(col, flp);
            f.setTag(new Object[]{id, main, nm, st, notches});
            tiles.addView(f, Ui.margins(new LayoutParams(w, h), tileViews.isEmpty() ? 0 : dp(20), 0, 0, 0));
            tileViews.add(f);
        }

        void refresh(VehicleClient.State s) {
            for (FrameLayout v : tileViews) {
                Object[] tag = (Object[]) v.getTag();
                String id = (String) tag[0];
                int state, level = -1;
                switch (id) {
                    case "ac": state = s.acOn; break;
                    case "auto": state = s.autoOn; break;
                    case "recirc": state = s.loopMode < 0 ? -1 : s.loopMode == 0 ? 1 : 0; break;
                    case "lka": state = s.lka; break;
                    case "opd": state = s.opd; break;
                    case "acc": state = s.accTja < 0 ? -1 : s.accTja == 4 ? 0 : 1; break;
                    case "pedal": state = s.pedal < 0 ? -1 : 0; level = pedalNotches(s.pedal); break;
                    case "wheel": state = s.wheelHeat; level = s.wheelHeat > 0 ? 1 : 0; break;
                    default:
                        level = id.equals("seatL") ? s.seatLeft : s.seatRight;
                        state = level < 0 ? -1 : level > 0 ? 1 : 0;
                }
                boolean on = state == 1;
                v.setAlpha(state < 0 ? 0.45f : 1f);
                float rad = dp(big ? 24 : 20 * Ui.K);
                TextView nm = (TextView) tag[2], st = (TextView) tag[3];
                String stateText = state < 0 ? "—" : (id.equals("seatL") || id.equals("seatR")) && on ? L.f(R.string.tile_level, level)
                        : id.equals("ac") ? L.s(on ? R.string.tile_on_f : R.string.tile_off_f) : L.s(on ? R.string.tile_on_m : R.string.tile_off_m);
                if (state >= 0 && id.equals("recirc")) {
                    stateText = recircName(s.loopMode);
                    ((ImageView) tag[1]).setImageResource(recircRes(s.loopMode));
                }
                if (state >= 0 && id.equals("acc")) {
                    stateText = s.accTja == 4 ? L.s(R.string.tile_off_m) : accName(s.accTja);
                    ((TextView) tag[1]).setText(accName(s.accTja));
                }
                if (state >= 0 && id.equals("pedal")) stateText = pedalName(s.pedal);
                st.setText(stateText);
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(rad);
                if (clima) {
                    // accesa = casella bianca con testo scuro, come il pulsante A/C
                    g.setColor(on ? t.text : t.chip);
                    int fg = on ? t.panel : t.text;
                    if (tag[1] instanceof TextView) ((TextView) tag[1]).setTextColor(fg);
                    else ((ImageView) tag[1]).setColorFilter(fg);
                    nm.setTextColor(fg);
                    st.setTextColor(on ? (t.panel & 0x00FFFFFF) | 0xB3000000 : t.sub);
                    LinearLayout notches = (LinearLayout) tag[4];
                    for (int i = 0; i < notches.getChildCount(); i++) {
                        notches.getChildAt(i).setBackground(Ui.round(i < level ? t.text : t.track, dp(3)));
                    }
                } else {
                    // accesa = sfondo caldo, bordo arancione, icona e stato arancioni, tacche del livello
                    g.setColor(on ? (t.day ? 0xFFFFF1EA : 0xFF2E241F) : t.chip);
                    if (on) g.setStroke(dp(2), HEAT);
                    ((ImageView) tag[1]).setColorFilter(on ? HEAT : t.text);
                    nm.setTextColor(t.text);
                    st.setTextColor(on ? HEAT : t.sub);
                    LinearLayout notches = (LinearLayout) tag[4];
                    for (int i = 0; i < notches.getChildCount(); i++) {
                        notches.getChildAt(i).setBackground(Ui.round(i < level ? HEAT : t.track, dp(3)));
                    }
                }
                v.setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(t.day ? 0x22000000 : 0x33FFFFFF), g, null));
            }
        }

        /** Riquadro centrato sopra il pulsante del gruppo (dentro lo schermo), punta sul pulsante, buco nello scuro. */
        private void place() {
            View anchor = guida ? guidaGroup : clima ? climaGroup : comfortGroup;
            if (anchor == null || barView == null || card.getWidth() == 0) return;
            int[] a = new int[2], root = new int[2], bar = new int[2];
            anchor.getLocationOnScreen(a);
            catcher.getLocationOnScreen(root);
            barView.getLocationOnScreen(bar);
            float ax = a[0] - root[0], ay = a[1] - root[1];
            catcher.hole.set(ax, ay, ax + anchor.getWidth(), ay + anchor.getHeight());
            float cx = ax + anchor.getWidth() / 2f;
            float x = Math.max(dp(20), Math.min(catcher.getWidth() - card.getWidth() - dp(20), cx - card.getWidth() / 2f));
            float y = bar[1] - root[1] - card.getHeight() - dp(20);
            card.setX(x);
            card.setY(y);
            arrow.setX(cx - arrow.getWidth() / 2f);
            arrow.setY(y + card.getHeight() - 1);
            card.setVisibility(VISIBLE);
            arrow.setVisibility(VISIBLE);
            catcher.invalidate();
        }

        void show() {
            host.overlay().addView(catcher, new FrameLayout.LayoutParams(-1, -1));
            touched();
        }

        void touched() {
            catcher.removeCallbacks(closer);
            catcher.postDelayed(closer, AUTO_CLOSE_MS);
        }

        void close() {
            catcher.removeCallbacks(closer);
            if (catcher.getParent() != null) ((ViewGroup) catcher.getParent()).removeView(catcher);
            if (popup == this) popup = null;
        }
    }

    private static String tempLabel(int v) {
        if (v <= VehicleClient.TEMP_MIN) return "LO";
        if (v >= VehicleClient.TEMP_MAX) return "HI";
        return v + "°";
    }

    private void changeTemp(int delta) {
        if (acTemp <= 0) acTemp = vehicle.last().acTemp;
        if (acTemp <= 0) return;
        acTemp = Math.max(VehicleClient.TEMP_MIN, Math.min(VehicleClient.TEMP_MAX, acTemp + delta));
        localTempUntil = SystemClock.uptimeMillis() + 4000;
        tempTv.setText(tempLabel(acTemp));
        vehicle.setDriverTemp(acTemp);
    }

    private static String fanLabel(int v) {
        if (v == VehicleClient.FAN_AUTO) return "A";
        if (v <= 0) return "OFF";
        return String.valueOf(v);
    }

    /** Come l'originale: da spenta o automatico il primo tocco parte dal minimo/massimo. */
    private void changeFan(int delta) {
        if (fanLevel < 0) fanLevel = vehicle.last().fanLevel;
        int cur = fanLevel;
        int next;
        if (cur == VehicleClient.FAN_AUTO) next = delta > 0 ? fanMax : fanMax - 1;
        else if (cur <= 0) next = delta > 0 ? fanMin : 0;
        else next = cur + delta;
        if (next < fanMin) next = VehicleClient.FAN_OFF;
        if (next > fanMax) next = fanMax;
        if (next == cur) return;
        fanLevel = next;
        localFanUntil = SystemClock.uptimeMillis() + 4000;
        fanTv.setText(fanLabel(fanLevel));
        vehicle.setFanLevel(fanLevel);
    }
}
