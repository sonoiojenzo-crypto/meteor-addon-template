package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBalance = settings.createGroup("Saldo e Record");

    // ----- Parole chiave da cercare nel messaggio (ripulito da simboli/colori) -----
    private final Setting<String> sellKeyword = sgGeneral.add(new StringSetting.Builder()
        .name("parola-vendita")
        .description("Parola chiave che identifica un messaggio di vendita.")
        .defaultValue("venduto")
        .build()
    );

    private final Setting<String> buyKeyword = sgGeneral.add(new StringSetting.Builder()
        .name("parola-acquisto")
        .description("Parola chiave che identifica un messaggio di acquisto.")
        .defaultValue("acquistato")
        .build()
    );

    // ----- Impostazioni scontrino -----
    private final Setting<String> receiptFormat = sgGeneral.add(new StringSetting.Builder()
        .name("formato-scontrino")
        .description("Usa {earned}, {spent}, {sold_items}, {bought_items}, {balance}, {record} come segnaposto. Evita caratteri speciali/unicode e a-capo, causano kick.")
        .defaultValue(
            "[SCONTRINO] Saldo: €{balance} -- Guadagnato: €{earned} (vendendo: {sold_items}) -- Speso: €{spent} (acquistando: {bought_items}) {record}"
        )
        .build()
    );

    private final Setting<Integer> intervalMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("intervallo-minuti")
        .description("Ogni quanti minuti stampare lo scontrino.")
        .defaultValue(60)
        .min(1)
        .sliderMax(180)
        .build()
    );

    private final Setting<Boolean> sendToServer = sgGeneral.add(new BoolSetting.Builder()
        .name("invia-in-chat-pubblica")
        .description("Se attivo manda lo scontrino come messaggio reale al server (visibile agli altri). Se disattivo, lo mostra solo a te.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> silentIfNoActivity = sgGeneral.add(new BoolSetting.Builder()
        .name("silenzioso-se-nessuna-attivita")
        .description("Non stampa lo scontrino se non hai comprato/venduto nulla nell'ultima ora.")
        .defaultValue(true)
        .build()
    );

    // ----- Impostazioni per nascondere un item specifico -----
    private final Setting<Boolean> hideItemEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("nascondi-item")
        .description("Nasconde il nome di un item specifico nello scontrino.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> hiddenItemName = sgGeneral.add(new StringSetting.Builder()
        .name("nome-item-da-nascondere")
        .description("Nome dell'item da nascondere (non case-sensitive).")
        .defaultValue("white wool")
        .visible(hideItemEnabled::get)
        .build()
    );

    private final Setting<String> hiddenItemReplacement = sgGeneral.add(new StringSetting.Builder()
        .name("testo-sostitutivo")
        .description("Testo da mostrare al posto del nome dell'item nascosto.")
        .defaultValue("ITEM SEGRETO")
        .visible(hideItemEnabled::get)
        .build()
    );

    // ----- Impostazioni saldo/record -----
    private final Setting<Boolean> checkBalanceEnabled = sgBalance.add(new BoolSetting.Builder()
        .name("controlla-saldo")
        .description("Prima di ogni scontrino, esegue il comando saldo e legge la risposta.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> balanceCommand = sgBalance.add(new StringSetting.Builder()
        .name("comando-saldo")
        .description("Comando da eseguire, senza la barra iniziale.")
        .defaultValue("balance")
        .visible(checkBalanceEnabled::get)
        .build()
    );

    private final Setting<String> balanceKeyword = sgBalance.add(new StringSetting.Builder()
        .name("parola-chiave-saldo")
        .description("Parola che identifica il messaggio di risposta col saldo (es. 'Soldi').")
        .defaultValue("soldi")
        .visible(checkBalanceEnabled::get)
        .build()
    );

    private final Setting<Integer> balanceTimeoutSeconds = sgBalance.add(new IntSetting.Builder()
        .name("timeout-saldo-secondi")
        .description("Se non arriva risposta entro questo tempo, lo scontrino parte comunque senza saldo.")
        .defaultValue(5)
        .min(1)
        .sliderMax(30)
        .visible(checkBalanceEnabled::get)
        .build()
    );

    private final Setting<String> recordText = sgBalance.add(new StringSetting.Builder()
        .name("testo-record")
        .description("Testo aggiunto quando raggiungi un saldo mai visto prima.")
        .defaultValue("Hai raggiunto un nuovo record!!")
        .visible(checkBalanceEnabled::get)
        .build()
    );

    private final Setting<Double> recordBalance = sgBalance.add(new DoubleSetting.Builder()
        .name("record-saldo-interno")
        .defaultValue(0.0)
        .visible(() -> false)
        .build()
    );

    // ----- Stato interno -----
    private double earnedThisPeriod = 0;
    private double spentThisPeriod = 0;
    private long lastReportTime = 0;

    private final Set<String> soldItemsThisPeriod = new LinkedHashSet<>();
    private final Set<String> boughtItemsThisPeriod = new LinkedHashSet<>();

    private boolean reportPending = false;
    private boolean waitingForBalance = false;
    private long waitingSince = 0;

    // ===== NUOVO: dati giornalieri per la tabella HUD =====
    public static class DayStats {
        public double earned = 0;
        public double spent = 0;
        public final Set<String> soldItems = new LinkedHashSet<>();
        public final Set<String> boughtItems = new LinkedHashSet<>();
    }

    private final Map<String, DayStats> dailyStats = new LinkedHashMap<>();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Usato dall'elemento HUD per leggere i dati
    public Map<String, DayStats> getDailyStats() {
        return dailyStats;
    }

    private DayStats todayStats() {
        String key = LocalDate.now().format(DATE_FORMAT);
        return dailyStats.computeIfAbsent(key, k -> new DayStats());
    }
    // ===== FINE NUOVO =====

    private static final Pattern CLEANUP = Pattern.compile("[^\\p{L}\\p{N}\\s.,]");
    private static final Pattern FULL_PATTERN = Pattern.compile("(?i)(\\d+)\\s*x\\s*(.+?)\\s*per\\s*([0-9.,]+)");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(?i)per\\s*([0-9.,]+)");

    public ShopTracker() {
        super(AddonTemplate.CATEGORY, "shop-tracker", "Traccia guadagni/spese dello shop e stampa uno scontrino periodico.");
    }

    @Override
    public void onActivate() {
        earnedThisPeriod = 0;
        spentThisPeriod = 0;
        soldItemsThisPeriod.clear();
        boughtItemsThisPeriod.clear();
        reportPending = false;
        waitingForBalance = false;
        lastReportTime = System.currentTimeMillis();
    }

    private double parseAmount(String raw) {
        String cleaned = raw.replace(",", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String raw = event.getMessage().getString();
        String cleaned = CLEANUP.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
        String lower = cleaned.toLowerCase();

        if (waitingForBalance && lower.contains(balanceKeyword.get().toLowerCase())) {
            Matcher balanceMatch = balancePattern(cleaned);
            if (balanceMatch.find()) {
                double balance = parseAmount(balanceMatch.group(1));
                finalizeReport(balance);
                return;
            }
        }

        boolean isSell = lower.contains(sellKeyword.get().toLowerCase());
        boolean isBuy = lower.contains(buyKeyword.get().toLowerCase());

        if (!isSell && !isBuy) return;

        Matcher fullMatch = FULL_PATTERN.matcher(cleaned);
        if (fullMatch.find()) {
            String itemName = fullMatch.group(2).trim();
            double amount = parseAmount(fullMatch.group(3));

            DayStats day = todayStats(); // NUOVO

            if (isSell) {
                earnedThisPeriod += amount;
                soldItemsThisPeriod.add(itemName);
                day.earned += amount;           // NUOVO
                day.soldItems.add(itemName);     // NUOVO
            } else {
                spentThisPeriod += amount;
                boughtItemsThisPeriod.add(itemName);
                day.spent += amount;             // NUOVO
                day.boughtItems.add(itemName);   // NUOVO
            }
            return;
        }

        Matcher priceMatch = PRICE_PATTERN.matcher(cleaned);
        if (!priceMatch.find()) return;

        double amount = parseAmount(priceMatch.group(1));
        DayStats day = todayStats(); // NUOVO

        if (isSell) {
            earnedThisPeriod += amount;
            day.earned += amount; // NUOVO
        } else {
            spentThisPeriod += amount;
            day.spent += amount; // NUOVO
        }
    }

    private Matcher balancePattern(String cleaned) {
        Pattern p = Pattern.compile("(?i)" + Pattern.quote(balanceKeyword.get()) + "\\D{0,10}([0-9.,]+)");
        return p.matcher(cleaned);
    }

    private String buildItemList(Set<String> items) {
        if (items.isEmpty()) return "-";

        StringJoiner joiner = new StringJoiner(", ");
        for (String itemName : items) {
            String displayName = itemName;
            if (hideItemEnabled.get() && displayName.equalsIgnoreCase(hiddenItemName.get())) {
                displayName = hiddenItemReplacement.get();
            }
            joiner.add(displayName);
        }
        return joiner.toString();
    }

    private void finalizeReport(Double balance) {
        String recordLine = "";
        String balanceStr = balance != null ? String.format("%.2f", balance) : "-";

        if (balance != null && balance > recordBalance.get()) {
            recordLine = recordText.get();
            recordBalance.set(balance);
        }

        String out = receiptFormat.get()
            .replace("{earned}", String.format("%.2f", earnedThisPeriod))
            .replace("{spent}", String.format("%.2f", spentThisPeriod))
            .replace("{sold_items}", buildItemList(soldItemsThisPeriod))
            .replace("{bought_items}", buildItemList(boughtItemsThisPeriod))
            .replace("{balance}", balanceStr)
            .replace("{record}", recordLine);

        if (sendToServer.get()) {
            ChatUtils.sendPlayerMsg(out);
        } else {
            ChatUtils.info(out);
        }

        earnedThisPeriod = 0;
        spentThisPeriod = 0;
        soldItemsThisPeriod.clear();
        boughtItemsThisPeriod.clear();
        reportPending = false;
        waitingForBalance = false;
        lastReportTime = System.currentTimeMillis();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();

        if (reportPending && waitingForBalance) {
            long timeoutMs = balanceTimeoutSeconds.get() * 1000L;
            if (now - waitingSince >= timeoutMs) {
                finalizeReport(null);
            }
            return;
        }

        if (reportPending) return;

        long intervalMs = intervalMinutes.get() * 60_000L;
        if (now - lastReportTime < intervalMs) return;

        boolean hasActivity = earnedThisPeriod > 0 || spentThisPeriod > 0;
        if (silentIfNoActivity.get() && !hasActivity) {
            lastReportTime = now;
            return;
        }

        reportPending = true;

        if (checkBalanceEnabled.get() && MinecraftClient.getInstance().player != null) {
            waitingForBalance = true;
            waitingSince = now;
            MinecraftClient.getInstance().player.networkHandler.sendChatCommand(balanceCommand.get());
        } else {
            finalizeReport(null);
        }
    }
}
