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
    private Timer updateTimer;
    private Timer priceUpdateTimer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Runnable onUpdateCallback;
    private Runnable onPriceUpdateCallback;

    // Konfiguration der Wallet-Generierung
    private static final long MIN_WALLET_CREATION_PERIOD = 300;
    private long currentWalletCreationPeriod = 1000;
    private final double periodMultiplier = 0.95;
    private final int periodThreshold = 50;

    // Konfiguration der Marktstimmung
    private double buyBias = 0.50;

    // 🌟 NEUE KONSTANTEN: Limitierung der Dateigröße
    private static final long MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024; // 5 MB Limit
    private static final String BLOCKCHAIN_FILE_PATH = "blockchain.json";

    // Konfiguration der GUI-Aktualisierung
    private static final long GUI_UPDATE_PERIOD = 10000; // 10 Sekunden für Chart/Listen

    // HANDELS-GESCHWINDIGKEITSANALYSE
    private static final long INITIAL_MIN_DELAY = 900;
    private static long currentTradeMinDelay = INITIAL_MIN_DELAY;

    public NetworkSimulator(Blockchain blockchain, WalletManager walletManager, PriceSimulator priceSimulator) {
        this.blockchain = blockchain;
        this.walletManager = walletManager;
        this.priceSimulator = priceSimulator;
    }

    // --- ÖFFENTLICHE API ---

    public void setOnUpdate(Runnable callback) {
        this.onUpdateCallback = callback;
    }

    public void setOnPriceUpdate(Runnable callback) {
        this.onPriceUpdateCallback = callback;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setBuyBias(double bias) {
        this.buyBias = Math.max(0.0, Math.min(1.0, bias));
        System.out.printf("--- Markt-Bias aktualisiert: %.0f%% Kaufinteresse ---%n", bias * 100);
    }

    public void start() {
        if (running.getAndSet(true)) return;

        currentTradeMinDelay = INITIAL_MIN_DELAY;

        System.out.println("=== NETZWERK-SIMULATION GESTARTET ===");

        // WALLET-ERSTELLUNG STARTEN
        startWalletGeneration();

        // HANDELS-SIMULATION STARTEN
        transactionTimer = new Timer(true);
        System.out.println("→ Kauf/Verkauf-Simulationen (Frequenz passt sich der Anzahl der Wallets an)");
        scheduleNextTrade(5);

        // GUI-AKTUALISIERUNGS-TIMER STARTEN (10 Sekunden)
        updateTimer = new Timer(true);
        updateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                triggerUpdate();
            }
        }, 0, GUI_UPDATE_PERIOD);

        // PREIS-UPDATE-TIMER STARTEN (1 Sekunde)
        priceUpdateTimer = new Timer(true);
        priceUpdateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                triggerPriceUpdate();
            }
        }, 0, 1000);
    }

    public void stop() {
        running.set(false);

        stopWalletGeneration();

        if (transactionTimer != null) {
            transactionTimer.cancel();
            transactionTimer = null;
        }

        if (updateTimer != null) {
            updateTimer.cancel();
            updateTimer = null;
        }

        if (priceUpdateTimer != null) {
            priceUpdateTimer.cancel();
            priceUpdateTimer = null;
        }

        System.out.println("=== NETZWERK-SIMULATION GESTOPPT ===");
    }

    // --- WALLET-GENERIERUNGS-STEUERUNG ---

    public void startWalletGeneration() {
        if (running.get() && walletTimer == null) {
            walletTimer = new Timer(true);
            System.out.println("--- Wallet-Generierung wieder gestartet. ---");
            System.out.printf("→ Neue Wallet alle %.2fs (dynamisch, verlangsamt alle %d Wallets um %.0f%%)%n",
                    currentWalletCreationPeriod / 1000.0, periodThreshold, (100 - periodMultiplier * 100));
            scheduleNextWalletCreation(currentWalletCreationPeriod);
        }
    }

    public void stopWalletGeneration() {
        if (walletTimer != null) {
            walletTimer.cancel();
            walletTimer = null;
            System.out.println("--- Wallet-Generierung gestoppt. ---");
        }
    }

    // --- INTERNE HILFSMETHODEN ---

    private void triggerUpdate() {
        if (onUpdateCallback != null) {
            Platform.runLater(onUpdateCallback);
        }
    }

    private void triggerPriceUpdate() {
        if (onPriceUpdateCallback != null) {
            Platform.runLater(onPriceUpdateCallback);
        }
    }

    private static synchronized long getAndSetCurrentTradeMinDelay(int userCount, long minDelayBase, int reductionFactor, long minDelayFast) {
        long oldDelay = currentTradeMinDelay;

        long delayReduction = (long) userCount * reductionFactor;
        long newDelay = Math.max(minDelayFast, minDelayBase - delayReduction);

        currentTradeMinDelay = newDelay;
        return oldDelay;
    }


    private void scheduleNextWalletCreation(long delay) {
        if (!running.get() || walletTimer == null) return;

        walletTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!running.get()) return;

                Wallet newWallet = WalletManager.createWallet();

                int userWalletCount = WalletManager.getWallets().size() - 1;

                if (userWalletCount > 0 && userWalletCount % periodThreshold == 0) {
                    long newPeriod = (long) (currentWalletCreationPeriod * periodMultiplier);
                    currentWalletCreationPeriod = Math.max(newPeriod, MIN_WALLET_CREATION_PERIOD);

                    System.out.printf("--- WALLET-SCHWELLE ERREICHT (%d Wallets)! Neue Wallet-Erstellungsdauer: %.0fms (%.2fs) ---%n",
                            userWalletCount, (double)currentWalletCreationPeriod, currentWalletCreationPeriod / 1000.0);
                }

                Platform.runLater(() -> {
                    System.out.println("NEUE WALLET erstellt: " + newWallet.getAddress().substring(0, 16) + "...");
                });

                scheduleNextWalletCreation(currentWalletCreationPeriod);
            }
        }, delay);
    }


    private void scheduleNextTrade(long delay) {
        if (!running.get() || transactionTimer == null) return;

        transactionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!running.get()) return;

                simulateTrade();

                List<Wallet> allWallets = WalletManager.getWallets();
                int userWalletCount = allWallets.size() - 1;

                long maxDelayBase = 900;
                long minDelayBase = 300;
                long minDelayFast = 10;
                int reductionFactor = 1;

                long delayReduction = (long) userWalletCount * reductionFactor;

                long actualMinDelay = Math.max(minDelayFast, minDelayBase - delayReduction);
                long actualMaxDelay = Math.max(actualMinDelay, maxDelayBase - delayReduction);

                long oldActualMinDelay = getAndSetCurrentTradeMinDelay(userWalletCount, minDelayBase, reductionFactor, minDelayFast);

                if (actualMinDelay != oldActualMinDelay) {
                    System.out.printf("--- HANDELS-SCHWELLE GEÄNDERT (%d Wallets)! Neue Handelsspanne: %.0fms - %.0fms ---%n",
                            userWalletCount, (double)actualMinDelay, (double)actualMaxDelay);
                }

                long range = actualMaxDelay - actualMinDelay + 1;
                long nextDelay = actualMinDelay + new Random().nextInt((int) range);

                scheduleNextTrade(nextDelay);
            }
        }, delay);
    }

    /**
     * Prüft die Größe der Blockchain-Datei und setzt die Kette bis auf den Genesis Block zurück,
     * falls das Limit überschritten wird.
     * @return true, wenn die Kette zurückgesetzt wurde.
     */
    private boolean checkAndResetChain() {
        try {
            java.io.File file = new java.io.File(BLOCKCHAIN_FILE_PATH);

            if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                System.out.printf("🚨 ALARM: Blockchain-Datei (%.2f MB) überschreitet Limit (%.2f MB). Wird auf Genesis Block zurückgesetzt...%n",
                        file.length() / (1024.0 * 1024.0), MAX_FILE_SIZE_BYTES / (1024.0 * 1024.0));

                // 1. Kette zurücksetzen, behält Genesis Block (#0)
                blockchain.resetChain();

                // 2. Wallets neu berechnen: WICHTIG! Setzt die Balancen auf den Stand nach der Genesis-Transaktion zurück.
                WalletManager.recalculateAllBalances();
                WalletManager.saveWallets();

                // 3. Neue (kleine) Kette speichern (überschreibt die alte, große Datei)
                BlockchainPersistence.saveBlockchain(blockchain);

                // Da ein Reset die Chain verändert, muss ein UI Update an den Update-Timer gesendet werden.
                triggerUpdate();

                return true;
            }
        } catch (Exception e) {
            System.err.println("Fehler bei der Überprüfung/Zurücksetzung der Blockchain-Datei: " + e.getMessage());
        }
        return false;
    }


    private boolean simulateTrade() {
        // 1. Initialisierung und Vorbereitung
        List<Wallet> allWallets = WalletManager.getWallets();
        Wallet supplyWallet = WalletManager.SUPPLY_WALLET;
        Random r = new Random();

        List<Wallet> userWallets = allWallets.stream()
                .filter(w -> !w.getAddress().equals(supplyWallet.getAddress()))
                .toList();

        if (userWallets.isEmpty()) return false;

        // 2. Auswahl der Wallet und Handelsrichtung
        Wallet tradingWallet = userWallets.get(r.nextInt(userWallets.size()));

        boolean mustBuy = tradingWallet.getBalance() < 0.01;

        boolean isBuy;
        if (mustBuy) {
            isBuy = true;
        } else {
            isBuy = r.nextDouble() < this.buyBias;
        }

        // 3. Berechnung des Handelsbetrags
        double currentPrice = priceSimulator.getCurrentPrice();

        final double MIN_PERCENTAGE = 0.33;
        final double MAX_PERCENTAGE = 0.95;

        double actualTradePercentage = MIN_PERCENTAGE + (MAX_PERCENTAGE - MIN_PERCENTAGE) * r.nextDouble();

        double usdToTrade;

        if (isBuy) {
            usdToTrade = tradingWallet.getUsdBalance() * actualTradePercentage;
        } else {
            usdToTrade = (tradingWallet.getBalance() * actualTradePercentage) * currentPrice;
        }

        usdToTrade = Math.max(1.0, usdToTrade);
        usdToTrade = Math.min(usdToTrade, 100000000.0);

        double tradeAmountSC = Math.round((usdToTrade / currentPrice) * 1000.0) / 1000.0;
        double usdValue = tradeAmountSC * currentPrice;

        if (usdValue < 1.0 || tradeAmountSC < 0.001) {
            return false;
        }

        List<Transaction> txs = new ArrayList<>();

        // 4. Ausführung der Transaktion
        if (isBuy) {
            if (tradingWallet.getUsdBalance() < usdValue || supplyWallet.getBalance() < tradeAmountSC + 0.01) {
                return false;
            }

            try {
                tradingWallet.debitUsd(usdValue);
                txs.add(supplyWallet.createTransaction(tradingWallet.getAddress(), tradeAmountSC, "SIMULIERT: SC Kauf von Supply"));

                priceSimulator.executeTrade(tradeAmountSC, true);

                System.out.printf("SIMULIERT KAUF: %s... kaufte %.3f SC für %.2f USD (%.0f%%) | Neuer Preis: %.4f%n",
                        tradingWallet.getAddress().substring(0, 10), tradeAmountSC, usdValue, actualTradePercentage * 100, priceSimulator.getCurrentPrice());

            } catch (Exception ignored) { return false; }


        } else {
            if (tradingWallet.getBalance() < tradeAmountSC + 0.01) {
                return false;
            }

            try {
                tradingWallet.creditUsd(usdValue);
                txs.add(tradingWallet.createTransaction(MyChainGUI.EXCHANGE_ADDRESS, tradeAmountSC, "SIMULIERT: SC Verkauf an Exchange"));

                priceSimulator.executeTrade(tradeAmountSC, false);

                System.out.printf("SIMULIERT VERKAUF: %s... verkaufte %.3f SC für %.2f USD (%.0f%%) | Neuer Preis: %.4f%n",
                        tradingWallet.getAddress().substring(0, 10), tradeAmountSC, usdValue, actualTradePercentage * 100, priceSimulator.getCurrentPrice());

            } catch (Exception ignored) { return false; }
        }

        // 5. Mining und Speicherung
        if (!txs.isEmpty()) {
            blockchain.addBlock(txs);

            // 🛑 NEU: Prüfung und Zurücksetzung nach dem Mining
            checkAndResetChain();

            // Speichern und Wallets neu berechnen (wird auch bei Reset durchgeführt, aber hier zur Sicherheit für den Normalbetrieb)
            BlockchainPersistence.saveBlockchain(blockchain);
            WalletManager.recalculateAllBalances();
            WalletManager.saveWallets();

            return true;
        }
        return false;
    }
}