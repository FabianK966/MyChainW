package org.fintech;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Random; // Wichtig: Random muss importiert sein
import java.util.concurrent.CopyOnWriteArrayList;

public class WalletManager {
    private static final String WALLETS_FILE = "wallets.json";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Dedizierte Wallet für den Coin-Supply
    public static final Wallet SUPPLY_WALLET = new Wallet("admin");
    private static int nextWalletId = 1;

    private static List<Wallet> wallets = new CopyOnWriteArrayList<>();

    public static final WalletManager INSTANCE = new WalletManager();
    private WalletManager() {}

    public static int getAndIncrementNextId() {
        return nextWalletId++;
    }

    public static void loadWallets() {
        File file = new File(WALLETS_FILE);

        if (file.exists() && file.length() > 0) {
            try (Reader reader = new FileReader(file)) {
                Type listType = new TypeToken<ArrayList<Wallet>>(){}.getType();
                wallets = gson.fromJson(reader, listType);
                if (wallets == null) wallets = new ArrayList<>();

                int maxId = wallets.stream()
                        .mapToInt(Wallet::getUniqueId) // ACHTUNG: Setzt voraus, dass Wallet.getUniqueId() nun int liefert
                        .max()
                        .orElse(0);

                nextWalletId = maxId + 1;
                System.out.println("System meldet: Nächste Wallet ID startet bei: " + nextWalletId);
            } catch (Exception e) {
                System.err.println("Fehler beim Laden der Wallets: " + e.getMessage());
                wallets = new ArrayList<>();
            }
        }
        else {
            wallets = new ArrayList<>();
            SUPPLY_WALLET.setUsdBalance(0.0);
            wallets.add(SUPPLY_WALLET);

            // Erste Benutzer-Wallet mit spezieller Initialisierung
            Wallet firstUser = createNewUserWallet();
            wallets.add(firstUser);
        }
        System.out.println(wallets.size() + " Wallet(s) geladen/initialisiert.");
        recalculateAllBalances();
    }

    public static synchronized void saveWallets() {
        try (Writer writer = new FileWriter(WALLETS_FILE)) {
            gson.toJson(wallets, writer);
            System.out.println("Wallets erfolgreich gespeichert.");
        } catch (IOException e) {
            System.err.println("Fehler beim Speichern der Wallets: " + e.getMessage());
        }
    }

    // 🌟 NEUE METHODE: Wallet erstellen mit geschichteter Balance-Logik
    private static Wallet createNewUserWallet() {
        Random r = new Random();

        // Die Zählung der User-Wallets ist die Gesamtanzahl der Wallets (minus die Supply Wallet)
        // Die Supply Wallet ist index 0, daher ist die Anzahl der User-Wallets: wallets.size() - 1
        int userWalletCount = wallets.size() - 1;

        double startingUsd;

        // Logik: "Jede 8-12. Wallet soll über 500.000 USD haben"
        // Wir verwenden Modulo 12, und die Nummern 8, 9, 10, 11, 0 (entspricht 12) erhalten eine große Balance.
        // Das bedeutet: (count % 12) >= 7 oder (count % 12) == 0

        // userWalletCount ist die Anzahl der bereits existierenden Wallets. Die neue Wallet ist userWalletCount + 1
        int newWalletIndexInCycle = (userWalletCount + 1) % 12;

        if (newWalletIndexInCycle >= 8 || newWalletIndexInCycle == 0) {
            // Große Balance: 50.000 USD bis 1.000.000 USD
            double minLarge = 500000.0;
            double maxLarge = 10000000.0;
            startingUsd = minLarge + (maxLarge - minLarge) * r.nextDouble();
            System.out.printf("GROSSE WALLET erstellt (#%d): %.2f USD%n", userWalletCount + 1, startingUsd);
        } else {
            // Normale Balance: 1.000 USD bis < 500.000 USD
            double minNormal = 5000.0;
            double maxNormal = 499999.9;
            startingUsd = minNormal + (maxNormal - minNormal) * r.nextDouble();
            System.out.printf("NORMALE WALLET erstellt (#%d): %.2f USD%n", userWalletCount + 1, startingUsd);
        }

        return new Wallet(StringUtil.generateRandomPassword(), startingUsd);
    }

    public static synchronized Wallet createWallet() {
        Wallet newWallet = createNewUserWallet(); // 🌟 Nutzt die neue Logik
        wallets.add(newWallet);
        saveWallets();

        // 100 SC Grant von der SUPPLY_WALLET (bleibt gleich)
        try {
            List<Transaction> grantTx = new ArrayList<>();
            Transaction tx = SUPPLY_WALLET.createTransaction(newWallet.getAddress(), 0.0, "Initial Wallet Grant (100 SC)");
            grantTx.add(tx);

            Blockchain blockchain = BlockchainPersistence.loadBlockchain("MyChain", 1);
            blockchain.addBlock(grantTx);
            BlockchainPersistence.saveBlockchain(blockchain);

            recalculateAllBalances();

        } catch (RuntimeException e) {
            System.err.println("Fehler beim Initial-SC-Grant: " + e.getMessage());
        }

        return newWallet;
    }

    public static List<Wallet> getWallets() {
        return wallets;
    }

    public static Wallet findWalletByAddress(String addr) {
        return wallets.stream()
                .filter(w -> w.getAddress().equals(addr))
                .findFirst()
                .orElse(null);
    }

    public static synchronized void recalculateAllBalances() {
        for (Wallet w : wallets) w.setBalance(0.0);

        Blockchain chain = BlockchainPersistence.loadBlockchain("MyChain", 1);

        for (Block block : chain.getChain()) {
            for (Transaction tx : block.getTransactions()) {
                String sender = tx.getSender();
                String recipient = tx.getRecipient();
                double amount = tx.getAmount();

                boolean isCoinbase = "system".equals(sender) || sender == null || sender.isEmpty();
                boolean isExchangeSell = MyChainGUI.EXCHANGE_ADDRESS.equals(recipient);

                if (!isCoinbase) {
                    Wallet from = findWalletByAddress(sender);
                    if (from != null) from.debit(amount);
                }

                if (!isExchangeSell) {
                    Wallet to = findWalletByAddress(recipient);
                    if (to != null) to.credit(amount);
                }
            }
        }
        saveWallets();
        System.out.println("Balances erfolgreich korrigiert und neu berechnet!");
    }
}