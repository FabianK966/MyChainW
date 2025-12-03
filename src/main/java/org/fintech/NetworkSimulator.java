package org.fintech;

import javafx.application.Platform;
import java.util.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class NetworkSimulator {

    private final Blockchain blockchain;
    private final WalletManager walletManager;
    private final PriceSimulator priceSimulator;
    private Timer walletTimer;
    private Timer transactionTimer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Runnable onUpdateCallback;
    private static final long MIN_WALLET_CREATION_PERIOD = 10;
    private long currentWalletCreationPeriod = 2000;
    private final double periodMultiplier = 0.9;
    private final int periodThreshold = 50;

    public NetworkSimulator(Blockchain blockchain, WalletManager walletManager, PriceSimulator priceSimulator) {
        this.blockchain = blockchain;
        this.walletManager = walletManager;
        this.priceSimulator = priceSimulator;
    }

    public void setOnUpdate(Runnable callback) {
        this.onUpdateCallback = callback;
    }

    public void triggerUpdate() {
        if (onUpdateCallback != null) {
            Platform.runLater(onUpdateCallback);
        }
    }

    public void start() {
        if (running.getAndSet(true)) return;

        System.out.println("=== NETZWERK-SIMULATION GESTARTET ===");

        // DYNAMISCHE WALLET-ERSTELLUNG STARTEN
        walletTimer = new Timer(true);
        System.out.printf("→ Neue Wallet alle %.2fs (dynamisch, verlangsamt alle %d Wallets um %.0f%%)%n",
                currentWalletCreationPeriod / 1000.0, periodThreshold, (periodMultiplier * 100 - 100));
        scheduleNextWalletCreation(currentWalletCreationPeriod);

        transactionTimer = new Timer(true);

        // Timer 2: Handels-Simulation
        System.out.println("→ Kauf/Verkauf-Simulationen (Frequenz passt sich der Anzahl der Wallets an)");
        scheduleNextTrade(5);
    }

    // 🌟 NEUE METHODE: Dynamische Planung der nächsten Wallet-Erstellung
    private void scheduleNextWalletCreation(long delay) {
        if (!running.get()) return;

        walletTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!running.get()) return;

                Platform.runLater(() -> {
                    // 1. Wallet erstellen (erhöht die Wallet-Anzahl)
                    Wallet newWallet = WalletManager.createWallet();

                    // 2. 🌟 NEUE LOGIK: Verzögerung anpassen (Beschleunigung), wenn Schwelle erreicht
                    int userWalletCount = WalletManager.getWallets().size() - 1;

                    if (userWalletCount > 0 && userWalletCount % periodThreshold == 0) {
                        // Verzögerung reduzieren (Beschleunigen)
                        long newPeriod = (long) (currentWalletCreationPeriod * periodMultiplier);

                        // Mindestwert prüfen, damit es nicht zu schnell wird
                        currentWalletCreationPeriod = Math.max(newPeriod, MIN_WALLET_CREATION_PERIOD);

                        System.out.printf("--- WALLET-SCHWELLE ERREICHT (%d Wallets)! Neue Wallet-Erstellungsdauer: %.0fms (%.2fs) ---%n",
                                userWalletCount, (double)currentWalletCreationPeriod, currentWalletCreationPeriod / 1000.0);
                    }

                    triggerUpdate();
                    System.out.println("NEUE WALLET erstellt: " + newWallet.getAddress().substring(0, 16) + "...");

                    // 3. Nächste Erstellung mit der (möglicherweise) neuen Verzögerung planen
                    scheduleNextWalletCreation(currentWalletCreationPeriod);
                });
            }
        }, delay);
    }


    private void scheduleNextTrade(long delay) {
        if (!running.get()) return;

        transactionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!running.get()) return;

                // 1. Handelslogik ausführen (läuft auf dem TimerTask-Thread)
                simulateTrade();

                // 2. Dynamische Verzögerung basierend auf Wallet-Anzahl
                List<Wallet> allWallets = WalletManager.getWallets();
                int userWalletCount = allWallets.size() - 1;

                long maxDelay = 250;
                long minDelayBase = 150;
                long minDelayFast = 50;

                int reductionFactor = 30;

                long delayReduction = (long) userWalletCount * reductionFactor;

                long actualMinDelay = Math.max(minDelayFast, minDelayBase - delayReduction);

                long nextDelay = actualMinDelay + new Random().nextInt((int) (maxDelay - actualMinDelay + 1));

                // 3. Nächsten Trade mit zufälliger Verzögerung planen
                scheduleNextTrade(nextDelay);
                System.out.println(nextDelay);
            }
        }, delay);
    }

    private void simulateTrade() {
        // ... (unveränderte Initialisierungen und Checks) ...
        List<Wallet> allWallets = WalletManager.getWallets();
        Wallet supplyWallet = WalletManager.SUPPLY_WALLET;
        Random r = new Random();

        List<Wallet> userWallets = allWallets.stream()
                .filter(w -> !w.getAddress().equals(supplyWallet.getAddress()))
                .toList();

        if (userWallets.isEmpty()) return;

        Wallet tradingWallet = userWallets.get(r.nextInt(userWallets.size()));
        boolean isBuy = r.nextBoolean();

        // 🌟 NEUE LOGIK: Bestimme den Handelsbetrag basierend auf 33% bis 90% der USD-Balance

        double currentPrice = priceSimulator.getCurrentPrice();

        // Zufälliger Prozentsatz zwischen 33.0% und 90.0%
        double percentage = 0.33 + r.nextDouble() * (0.90 - 0.33);

        double maxUsdToTrade = tradingWallet.getUsdBalance() * percentage;

        // Sicherstellen, dass der Betrag nicht null ist, aber auch nicht zu groß.
        // Wir begrenzen den zufälligen USD-Handelsbetrag.
        double usdToTrade = Math.max(1.0, r.nextDouble() * maxUsdToTrade);

        // Sicherstellen, dass der Betrag nicht 0 ist, und auf maximal 1.000.000 USD begrenzen,
        // um übermäßige Schwankungen durch eine einzige Wallet zu vermeiden.
        usdToTrade = Math.min(usdToTrade, 1000000.0);

        // Konvertierung in SC-Coins für die Blockchain-Transaktion
        double tradeAmountSC = usdToTrade / currentPrice;

        // Wir runden den SC-Betrag auf 3 Dezimalstellen, um die Logik sauberer zu halten
        tradeAmountSC = Math.round(tradeAmountSC * 1000.0) / 1000.0;

        double usdValue = tradeAmountSC * currentPrice; // Der tatsächliche USD-Wert des SC-Betrags

        if (usdValue < 1.0 || tradeAmountSC < 0.001) {
            // Verhindern von extrem kleinen Transaktionen
            return;
        }

        List<Transaction> txs = new ArrayList<>();

        if (isBuy) {
            // SC KAUFEN (USD -> SC, von Supply)
            if (tradingWallet.getUsdBalance() < usdValue || supplyWallet.getBalance() < tradeAmountSC + 0.01) {
                return;
            }

            try {
                tradingWallet.debitUsd(usdValue);
                txs.add(supplyWallet.createTransaction(tradingWallet.getAddress(), tradeAmountSC, "SIMULIERT: SC Kauf von Supply"));

                priceSimulator.executeTrade(tradeAmountSC, true);

                System.out.printf("SIMULIERT KAUF: %s... kaufte %.3f SC für %.2f USD (%.0f%% der USD-Balance) | Neuer Preis: %.4f%n",
                        tradingWallet.getAddress().substring(0, 10), tradeAmountSC, usdValue, percentage * 100, priceSimulator.getCurrentPrice());

            } catch (Exception ignored) { return; }


        } else {
            // SC VERKAUFEN (SC -> USD, an Exchange)
            // Wir prüfen, ob der Wallet genug SC hat, um den TradeAmountSC zu decken.
            if (tradingWallet.getBalance() < tradeAmountSC + 0.01) {
                return;
            }

            try {
                // Wir schreiben den vollen USD-Wert (usdValue) gut.
                tradingWallet.creditUsd(usdValue);
                txs.add(tradingWallet.createTransaction(MyChainGUI.EXCHANGE_ADDRESS, tradeAmountSC, "SIMULIERT: SC Verkauf an Exchange"));

                priceSimulator.executeTrade(tradeAmountSC, false);

                // Da beim Verkauf SC gegen USD getauscht wird, ist die ursprüngliche USD-Balance des
                // Käufers irrelevant, aber wir können den Prozentsatz der SC-Balance anzeigen, wenn Sie möchten.
                // Um die Logik konsistent zu halten, zeigen wir weiterhin den Prozentsatz der
                // USD-Änderung an (die den Wert des gehandelten SC darstellt).
                System.out.printf("SIMULIERT VERKAUF: %s... verkaufte %.3f SC für %.2f USD (%.0f%% der sim. Kaufkraft) | Neuer Preis: %.4f%n",
                        tradingWallet.getAddress().substring(0, 10), tradeAmountSC, usdValue, percentage * 100, priceSimulator.getCurrentPrice());

            } catch (Exception ignored) { return; }
        }

        if (!txs.isEmpty()) {
            // BLOCK-MINING UND SPEICHERUNG LAUFEN HIER AUF DEM HINTERGRUND-THREAD!
            blockchain.addBlock(txs);
            BlockchainPersistence.saveBlockchain(blockchain);
            WalletManager.recalculateAllBalances();
            WalletManager.saveWallets();

            // NUR DIE GUI-AKTUALISIERUNG WIRD AUF DEM JAVA-FX-THREAD ausgeführt
            Platform.runLater(this::triggerUpdate);
        }
    }


    public void stop() {
        running.set(false);

        if (walletTimer != null) {
            walletTimer.cancel();
            walletTimer = null;
        }
        if (transactionTimer != null) {
            transactionTimer.cancel();
            transactionTimer = null;
        }
        System.out.println("=== NETZWERK-SIMULATION GESTOPPT ===");
    }

    public boolean isRunning() {
        return running.get();
    }
}