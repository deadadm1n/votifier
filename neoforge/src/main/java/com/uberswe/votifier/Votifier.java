package com.uberswe.votifier;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Mod(Constants.MOD_ID)
public class Votifier {
    private VotifierServer votifierServer;
    private ApiVotePoller apiVotePoller;
    private MinecraftServer mcServer;

    public Votifier() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        mcServer = event.getServer();
        try {
            Path configDir = FMLPaths.CONFIGDIR.get().resolve("votifier");
            VotifierConfig config = VotifierConfig.load(configDir);
            RSAKeyManager keyManager = new RSAKeyManager();
            keyManager.load(configDir);
            VoteStorage voteStorage = new VoteStorage(config);

            Constants.LOG.info("Votifier public key (paste this into your server list website):");
            Constants.LOG.info(keyManager.getPublicKeyBase64());
            Constants.LOG.info("NuVotifier v2 token is saved in config/votifier/config.json");

            votifierServer = new VotifierServer(config, keyManager, voteStorage);
            voteStorage.setVoteCallback(this::onVoteReceived);
            votifierServer.start();

            apiVotePoller = new ApiVotePoller(config, voteStorage);
            apiVotePoller.start();
        } catch (Exception e) {
            Constants.LOG.error("Failed to start Votifier", e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (apiVotePoller != null) {
            apiVotePoller.stop();
            apiVotePoller = null;
        }
        if (votifierServer != null) {
            votifierServer.stop();
        }
        mcServer = null;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("vote")
                .executes(ctx -> showVoteHelp(ctx.getSource()))
                .then(Commands.literal("balance")
                        .executes(ctx -> showBalance(ctx.getSource())))
                .then(Commands.literal("shop")
                        .executes(ctx -> showShop(ctx.getSource())))
                .then(Commands.literal("buy")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .executes(ctx -> buyShopItem(ctx.getSource(), StringArgumentType.getString(ctx, "item")))))
                .then(Commands.literal("top")
                        .executes(ctx -> showTop(ctx.getSource())))
                .then(Commands.literal("admin")
                        .requires(this::canUseAdminCommands)
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer())
                                                .executes(ctx -> adminAdd(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "amount")
                                                )))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                                .executes(ctx -> adminSet(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "amount")
                                                )))))));
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (votifierServer == null) return;
        MinecraftServer server = event.getEntity().level().getServer();
        if (server == null) return;

        String playerName = event.getEntity().getName().getString();
        processVotes(server, playerName);
    }

    private void onVoteReceived(Vote vote) {
        if (mcServer == null) return;
        ServerPlayer player = mcServer.getPlayerList().getPlayerByName(vote.getUsername());
        if (player != null) {
            mcServer.execute(() -> processVotes(mcServer, vote.getUsername()));
        }
    }

    private void processVotes(MinecraftServer server, String playerName) {
        List<Vote> votes = votifierServer.getVoteStorage().getAndRemoveVotes(playerName);
        if (!votes.isEmpty()) {
            Constants.LOG.info("Processing {} pending vote reward(s) for {}", votes.size(), playerName);
            for (Vote vote : votes) {
                for (String command : votifierServer.getConfig().getVoteCommands()) {
                    runCommand(server, command, playerName, votifierServer.getConfig().getVotePointsPerVote(), null);
                }
            }
        }
    }

    private int showVoteHelp(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Vote commands: /vote balance, /vote shop, /vote buy <item>, /vote top"), false);
        return 1;
    }

    private int showBalance(CommandSourceStack source) {
        if (votifierServer == null) return 0;
        try {
            String player = source.getPlayerOrException().getName().getString();
            VoteStorage storage = votifierServer.getVoteStorage();
            source.sendSuccess(() -> Component.literal("Vote balance: " + storage.getBalance(player) + " | Lifetime votes: " + storage.getLifetimeVotes(player)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
    }

    private int showShop(CommandSourceStack source) {
        if (votifierServer == null) return 0;
        VotifierConfig.VoteShopConfig shop = votifierServer.getConfig().getVoteShop();
        if (!shop.isEnabled() || shop.getItems().isEmpty()) {
            source.sendSuccess(() -> Component.literal("The vote shop is empty."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Vote shop:"), false);
        for (VotifierConfig.VoteShopItem item : shop.getItems()) {
            source.sendSuccess(() -> Component.literal(item.getId() + " - " + item.getName() + " (" + item.getCost() + " votes) " + item.getDescription()), false);
        }
        source.sendSuccess(() -> Component.literal("Buy with /vote buy <item>"), false);
        return 1;
    }

    private int buyShopItem(CommandSourceStack source, String itemId) {
        if (votifierServer == null || mcServer == null) return 0;
        try {
            String player = source.getPlayerOrException().getName().getString();
            VotifierConfig.VoteShopConfig shop = votifierServer.getConfig().getVoteShop();
            VotifierConfig.VoteShopItem item = shop.findItem(itemId);
            if (!shop.isEnabled() || item == null) {
                source.sendFailure(Component.literal("Unknown vote shop item: " + itemId));
                return 0;
            }
            VoteStorage storage = votifierServer.getVoteStorage();
            if (!storage.spend(player, item.getCost())) {
                source.sendFailure(Component.literal("Not enough votes. You have " + storage.getBalance(player) + " and need " + item.getCost() + "."));
                return 0;
            }
            for (String command : item.getCommands()) {
                runCommand(mcServer, command, player, item.getCost(), item);
            }
            source.sendSuccess(() -> Component.literal("Bought " + item.getName() + ". New balance: " + storage.getBalance(player)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can buy vote shop items."));
            return 0;
        }
    }

    private int showTop(CommandSourceStack source) {
        if (votifierServer == null) return 0;
        List<Map.Entry<String, PlayerVoteData>> top = votifierServer.getVoteStorage().topBalances(10);
        if (top.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No vote balances yet."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Top vote balances:"), false);
        int rank = 1;
        for (Map.Entry<String, PlayerVoteData> entry : top) {
            int position = rank++;
            source.sendSuccess(() -> Component.literal(position + ". " + entry.getKey() + " - " + entry.getValue().getBalance()), false);
        }
        return 1;
    }

    private int adminAdd(CommandSourceStack source, String player, int amount) {
        if (votifierServer == null) return 0;
        votifierServer.getVoteStorage().addBalance(player, amount);
        source.sendSuccess(() -> Component.literal("Added " + amount + " vote(s) to " + player + ". Balance: " + votifierServer.getVoteStorage().getBalance(player)), true);
        return 1;
    }

    private int adminSet(CommandSourceStack source, String player, int amount) {
        if (votifierServer == null) return 0;
        votifierServer.getVoteStorage().setBalance(player, amount);
        source.sendSuccess(() -> Component.literal("Set " + player + " vote balance to " + amount + "."), true);
        return 1;
    }

    private boolean canUseAdminCommands(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            return source.getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()));
        } catch (Exception e) {
            return true;
        }
    }

    private void runCommand(MinecraftServer server, String command, String playerName, int points, VotifierConfig.VoteShopItem item) {
        String cmd = command
                .replace("{player}", playerName)
                .replace("{points}", String.valueOf(points));
        if (item != null) {
            cmd = cmd
                    .replace("{item}", item.getId())
                    .replace("{item_name}", item.getName())
                    .replace("{cost}", String.valueOf(item.getCost()));
        }
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
    }
}
