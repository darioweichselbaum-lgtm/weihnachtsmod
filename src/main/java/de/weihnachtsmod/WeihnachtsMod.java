package de.weihnachtsmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Serverseitige Weihnachtsmod (Fabric). Clients brauchen die Mod NICHT.
 *
 * Features:
 *  - Begrüßung mit Weihnachts-Countdown beim Beitreten
 *  - /adventskalender : tägliches Türchen vom 1.-24. Dezember (pro Spieler einmal pro Tag)
 *  - /weihnachten     : Countdown bis Heiligabend
 *  - /schneemann      : baut vor dir einen Schneemann (echter Schneegolem)
 *  - Schneeflocken-Partikel um die Spieler in der Weihnachtszeit
 *  - /weihnachtslied  : spielt Weihnachtslieder als Notenblock-Melodien (ohne Ressourcenpaket)
 */
public class WeihnachtsMod implements ModInitializer {

    public static final String MOD_ID = "weihnachtsmod";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SAVE_TYPE = new TypeToken<Map<String, Set<Integer>>>() {}.getType();
    private static final Random RANDOM = new Random();

    /** Schlüssel: "<Jahr>:<UUID>" -> geöffnete Türchen */
    private static Map<String, Set<Integer>> opened = new HashMap<>();

    /** Cooldown für /schneemann (UUID -> letzter Zeitpunkt in ms) */
    private static final Map<String, Long> snowmanCooldown = new HashMap<>();
    private static final long SNOWMAN_COOLDOWN_MS = 30_000;

    // ---------- Weihnachtslieder ----------

    /**
     * Ein Lied. Noten im Format "NOTE:DAUER", getrennt durch Leerzeichen.
     * NOTE = C4, F#4, A3 ... oder R für Pause. DAUER in Einheiten, eine Einheit = ticksPerUnit Ticks.
     * Alle Melodien sind gemeinfrei (traditionell).
     */
    private record Song(String title, Holder<SoundEvent> instrument, int ticksPerUnit, String notes,
                        String soundName, int seconds) {
        Song(String title, Holder<SoundEvent> instrument, int ticksPerUnit, String notes) {
            this(title, instrument, ticksPerUnit, notes, null, 0);
        }
        boolean isAudio() { return soundName != null; }
    }

    /** Aktuell laufende Audio-Aufnahmen (Resourcepack-Sounds), damit sie gestoppt werden können */
    private static final Map<UUID, Identifier> AUDIO = new HashMap<>();

    private static void stopAudio(ServerPlayer player) {
        Identifier id = AUDIO.remove(player.getUUID());
        if (id != null) {
            player.connection.send(new ClientboundStopSoundPacket(id, SoundSource.RECORDS));
        }
    }

    private static final Map<String, Song> SONGS = new LinkedHashMap<>();

    static {
        // Jingle Bells (Refrain), 4/4, Einheit = Achtel
        SONGS.put("jingle-bells", new Song("Jingle Bells", SoundEvents.NOTE_BLOCK_BELL, 3,
                "E4:2 E4:2 E4:4 E4:2 E4:2 E4:4 E4:2 G4:2 C4:3 D4:1 E4:8 "
              + "F4:2 F4:2 F4:3 F4:1 F4:2 E4:2 E4:2 E4:1 E4:1 E4:2 D4:2 D4:2 E4:2 D4:4 G4:4 "
              + "E4:2 E4:2 E4:4 E4:2 E4:2 E4:4 E4:2 G4:2 C4:3 D4:1 E4:8 "
              + "F4:2 F4:2 F4:3 F4:1 F4:2 E4:2 E4:2 E4:1 E4:1 G4:2 G4:2 F4:2 D4:2 C4:8"));

        // Stille Nacht, 6/8, Einheit = Sechzehntel
        SONGS.put("stille-nacht", new Song("Stille Nacht, heilige Nacht", SoundEvents.NOTE_BLOCK_HARP, 4,
                "G4:3 A4:1 G4:2 E4:6 G4:3 A4:1 G4:2 E4:6 "
              + "D5:4 D5:2 B4:6 C5:4 C5:2 G4:6 "
              + "A4:4 A4:2 C5:3 B4:1 A4:2 G4:3 A4:1 G4:2 E4:6 "
              + "A4:4 A4:2 C5:3 B4:1 A4:2 G4:3 A4:1 G4:2 E4:6 "
              + "D5:4 D5:2 F5:3 D5:1 B4:2 C5:12 "
              + "E5:6 C5:2 G4:2 E4:2 G4:3 F4:1 D4:2 C4:12"));

        // We Wish You a Merry Christmas, 3/4, Einheit = Achtel
        SONGS.put("we-wish-you", new Song("We Wish You a Merry Christmas", SoundEvents.NOTE_BLOCK_BELL, 4,
                "D4:2 G4:2 G4:1 A4:1 G4:1 F#4:1 E4:2 E4:2 E4:2 "
              + "A4:2 A4:1 B4:1 A4:1 G4:1 F#4:2 D4:2 D4:2 "
              + "B4:2 B4:1 C5:1 B4:1 A4:1 G4:2 E4:2 D4:1 D4:1 "
              + "E4:2 A4:2 F#4:2 G4:6"));

        // Morgen kommt der Weihnachtsmann (Melodie: "Ah! vous dirai-je, maman"), 4/4, Einheit = Achtel
        SONGS.put("morgen-kommt-der-weihnachtsmann", new Song("Morgen kommt der Weihnachtsmann", SoundEvents.NOTE_BLOCK_BELL, 4,
                "C4:2 C4:2 G4:2 G4:2 A4:2 A4:2 G4:4 F4:2 F4:2 E4:2 E4:2 D4:2 D4:2 C4:4 "
              + "G4:2 G4:2 F4:2 F4:2 E4:2 E4:2 D4:4 G4:2 G4:2 F4:2 F4:2 E4:2 E4:2 D4:4 "
              + "C4:2 C4:2 G4:2 G4:2 A4:2 A4:2 G4:4 F4:2 F4:2 E4:2 E4:2 D4:2 D4:2 C4:6"));

        // Deck the Halls (traditionell, walisch), 4/4, Einheit = Achtel
        SONGS.put("deck-the-halls", new Song("Deck the Halls", SoundEvents.NOTE_BLOCK_BELL, 3,
                "G4:3 F4:1 E4:2 D4:2 C4:2 D4:2 E4:2 C4:2 D4:3 E4:1 F4:2 D4:2 E4:3 D4:1 C4:2 B3:2 C4:8"));

        // Greensleeves / What Child Is This (traditionell), 3/4, Einheit = Achtel
        SONGS.put("greensleeves", new Song("Greensleeves", SoundEvents.NOTE_BLOCK_HARP, 4,
                "A4:2 C5:4 D5:2 E5:3 F5:1 E5:2 D5:4 B4:2 G4:3 A4:1 B4:2 C5:4 A4:2 "
              + "A4:3 G#4:1 A4:2 B4:4 G#4:2 E4:6 "
              + "A4:2 C5:4 D5:2 E5:3 F5:1 E5:2 D5:4 B4:2 G4:3 A4:1 B4:2 C5:4 A4:2 "
              + "A4:3 G#4:1 A4:2 B4:4 G#4:2 A4:6"));

        // Joy to the World (nur der Anfang), 4/4, Einheit = Achtel
        SONGS.put("joy-to-the-world", new Song("Joy to the World (Anfang)", SoundEvents.NOTE_BLOCK_BELL, 4,
                "C5:2 B4:3 A4:1 G4:2 F4:3 E4:1 D4:2 C4:6"));

        // Ode an die Freude (Beethoven, Thema), 4/4, Einheit = Achtel - passt gut zu Silvester
        SONGS.put("ode-an-die-freude", new Song("Freude, schöner Götterfunken", SoundEvents.NOTE_BLOCK_HARP, 3,
                "E4:2 E4:2 F4:2 G4:2 G4:2 F4:2 E4:2 D4:2 C4:2 C4:2 D4:2 E4:2 E4:3 D4:1 D4:4 "
              + "E4:2 E4:2 F4:2 G4:2 G4:2 F4:2 E4:2 D4:2 C4:2 C4:2 D4:2 E4:2 D4:3 C4:1 C4:4 "
              + "D4:2 D4:2 E4:2 C4:2 D4:2 E4:1 F4:1 E4:2 C4:2 D4:2 E4:1 F4:1 E4:2 D4:2 C4:2 D4:2 G3:4 "
              + "E4:2 E4:2 F4:2 G4:2 G4:2 F4:2 E4:2 D4:2 C4:2 C4:2 D4:2 E4:2 D4:3 C4:1 C4:4"));
    }

    /** Laufende Wiedergabe pro Spieler */
    private static final class Playback {
        final Song song;
        final float[] pitches;   // 0 = Pause
        final int[] durations;
        int index = 0;
        int wait = 0;

        Playback(Song song) {
            this.song = song;
            String[] tokens = song.notes().trim().split("\\s+");
            pitches = new float[tokens.length];
            durations = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                String[] parts = tokens[i].split(":");
                pitches[i] = pitchOf(parts[0]);
                durations[i] = Integer.parseInt(parts[1]);
            }
        }
    }

    private static final Map<UUID, Playback> PLAYBACKS = new HashMap<>();

    /** Notenname -> Notenblock-Tonhöhe (Notenblock: Fis3 .. Fis5, 25 Halbtöne). */
    private static float pitchOf(String name) {
        if (name.equals("R")) return 0f;
        int base = switch (name.charAt(0)) {
            case 'C' -> 0; case 'D' -> 2; case 'E' -> 4; case 'F' -> 5;
            case 'G' -> 7; case 'A' -> 9; case 'B' -> 11;
            default -> throw new IllegalArgumentException("Unbekannte Note: " + name);
        };
        boolean sharp = name.length() > 2 && name.charAt(1) == '#';
        int octave = name.charAt(name.length() - 1) - '0';
        int midi = 12 * (octave + 1) + base + (sharp ? 1 : 0);
        int n = midi - 54;                  // Fis3 = MIDI 54 = Notenblock-Stufe 0
        while (n < 0) n += 12;              // in den Notenblock-Bereich schieben
        while (n > 24) n -= 12;
        return (float) Math.pow(2.0, (n - 12) / 12.0);
    }

    // ---------- Eigene Lieder aus der Config ----------

    private static Path songsPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("weihnachtsmod_lieder.json");
    }

    private static Holder<SoundEvent> instrumentOf(String name) {
        return switch (name.toLowerCase()) {
            case "bell" -> SoundEvents.NOTE_BLOCK_BELL;
            case "chime" -> SoundEvents.NOTE_BLOCK_CHIME;
            case "flute" -> SoundEvents.NOTE_BLOCK_FLUTE;
            case "pling" -> SoundEvents.NOTE_BLOCK_PLING;
            case "guitar" -> SoundEvents.NOTE_BLOCK_GUITAR;
            case "xylophone" -> SoundEvents.NOTE_BLOCK_XYLOPHONE;
            default -> SoundEvents.NOTE_BLOCK_HARP;
        };
    }

    /** Liest config/weihnachtsmod_lieder.json. Beim ersten Start wird eine Beispieldatei angelegt. */
    private static void loadCustomSongs() {
        Path p = songsPath();
        if (!Files.exists(p)) {
            String example = """
                    {
                      "lieder": {
                        "beispiel-lied": {
                          "titel": "Beispiel-Lied",
                          "instrument": "bell",
                          "tempo": 4,
                          "noten": "C4:2 D4:2 E4:2 F4:2 G4:4 R:4 G4:4"
                        }
                      }
                    }
                    """;
            try {
                Files.writeString(p, example);
            } catch (IOException e) {
                System.err.println("[" + MOD_ID + "] Konnte Beispiel-Liederdatei nicht anlegen: " + e);
            }
        }
        try (Reader r = Files.newBufferedReader(p)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null || !root.has("lieder")) return;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("lieder").entrySet()) {
                String key = e.getKey().toLowerCase();
                try {
                    JsonObject o = e.getValue().getAsJsonObject();
                    String title = o.has("titel") ? o.get("titel").getAsString() : key;
                    Holder<SoundEvent> inst = instrumentOf(o.has("instrument") ? o.get("instrument").getAsString() : "harp");
                    int tempo = o.has("tempo") ? Math.max(1, o.get("tempo").getAsInt()) : 4;
                    String ton = o.has("ton") ? o.get("ton").getAsString() : null;   // Name aus sounds.json des Resourcepacks
                    String noten = o.has("noten") ? o.get("noten").getAsString() : "";
                    if (ton == null && noten.isBlank()) throw new IllegalArgumentException("weder 'ton' noch 'noten' angegeben");
                    int dauer = o.has("dauer") ? Math.max(0, o.get("dauer").getAsInt()) : 0;   // Länge in Sekunden (für Shuffle)
                    Song song = new Song(title, inst, tempo, noten, ton, dauer);
                    if (!song.isAudio()) new Playback(song); // prüft die Noten auf Fehler
                    SONGS.put(key, song);
                } catch (RuntimeException ex) {
                    System.err.println("[" + MOD_ID + "] Lied '" + key + "' ist fehlerhaft und wird übersprungen: " + ex);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + MOD_ID + "] Konnte Liederdatei nicht laden: " + e);
        }
    }

    // ---------- Wiedergabe-Status pro Spieler (Shuffle / Skip) ----------

    private static final class Session {
        boolean shuffle = false;
        String filter = "alle";      // alle | noten | gesang
        String current = null;       // Befehlsname des aktuellen Liedes
        int audioTicksLeft = -1;     // Restdauer einer Aufnahme (-1 = unbekannt)
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static Session session(ServerPlayer p) {
        return SESSIONS.computeIfAbsent(p.getUUID(), k -> new Session());
    }

    /** Zufälliges Lied aus dem Filter (alle/noten/gesang), möglichst nicht das aktuelle. */
    private static String randomSong(String filter, String exclude) {
        List<String> pool = new ArrayList<>();
        for (Map.Entry<String, Song> e : SONGS.entrySet()) {
            boolean audio = e.getValue().isAudio();
            if (filter.equals("noten") && audio) continue;
            if (filter.equals("gesang") && !audio) continue;
            pool.add(e.getKey());
        }
        if (pool.size() > 1) pool.remove(exclude);
        return pool.isEmpty() ? null : pool.get(RANDOM.nextInt(pool.size()));
    }

    /** Nächstes Lied in der Reihenfolge der Liste. */
    private static String nextInOrder(String current) {
        List<String> keys = new ArrayList<>(SONGS.keySet());
        int i = current == null ? -1 : keys.indexOf(current);
        return keys.get((i + 1) % keys.size());
    }

    /** Spielt ein Lied für den Spieler ab. Ändert den Shuffle-Modus nicht. */
    private static void play(ServerPlayer target, String key) {
        Song song = SONGS.get(key);
        Session s = session(target);
        PLAYBACKS.remove(target.getUUID());
        stopAudio(target);
        s.current = key;
        s.audioTicksLeft = -1;
        if (song.isAudio()) {
            Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, song.soundName());
            AUDIO.put(target.getUUID(), id);
            target.playNotifySound(SoundEvent.createVariableRangeEvent(id), SoundSource.RECORDS, 1.0f, 1.0f);
            if (song.seconds() > 0) {
                s.audioTicksLeft = song.seconds() * 20 + 40;   // + 2 Sekunden Pause
            } else if (s.shuffle) {
                target.sendSystemMessage(Component.literal("Für dieses Lied ist keine \"dauer\" eingetragen, Shuffle springt hier nicht von selbst weiter. Nutze /weihnachtslied skip.")
                        .withStyle(ChatFormatting.RED));
            }
        } else {
            PLAYBACKS.put(target.getUUID(), new Playback(song));
        }
        target.sendSystemMessage(Component.literal((s.shuffle ? "🔀 " : "") + "♪ Es spielt: " + song.title() + " ♪")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    private static void tellOther(net.minecraft.commands.CommandSourceStack source, ServerPlayer target, String text) {
        if (source.getEntity() != target) {
            source.sendSuccess(() -> Component.literal(text + " (" + target.getName().getString() + ")")
                    .withStyle(ChatFormatting.GREEN), false);
        }
    }

    private static int startSong(net.minecraft.commands.CommandSourceStack source, ServerPlayer target, String key) {
        String k = key.toLowerCase();
        if (!SONGS.containsKey(k)) {
            source.sendFailure(Component.literal("Dieses Lied kenne ich nicht. Mit /weihnachtslied liste siehst du alle."));
            return 0;
        }
        session(target).shuffle = false;   // Wunschlied = Shuffle aus
        play(target, k);
        tellOther(source, target, "Lied gestartet");
        return 1;
    }

    private static int skipSong(net.minecraft.commands.CommandSourceStack source, ServerPlayer target) {
        Session s = session(target);
        String next = s.shuffle ? randomSong(s.filter, s.current) : nextInOrder(s.current);
        if (next == null) {
            source.sendFailure(Component.literal("Keine passenden Lieder gefunden."));
            return 0;
        }
        play(target, next);
        tellOther(source, target, "Übersprungen");
        return 1;
    }

    /** /weihnachtslied shuffle [alle|noten|gesang]: ohne Angabe schaltet es Shuffle ein bzw. aus. */
    private static int shuffleSong(net.minecraft.commands.CommandSourceStack source, ServerPlayer target, String modus) {
        Session s = session(target);
        if (modus != null && !(modus.equals("alle") || modus.equals("noten") || modus.equals("gesang"))) {
            source.sendFailure(Component.literal("Modus muss alle, noten oder gesang sein."));
            return 0;
        }
        if (s.shuffle && modus == null) {
            s.shuffle = false;
            target.sendSystemMessage(Component.literal("🔀 Shuffle aus. Das aktuelle Lied läuft noch zu Ende.")
                    .withStyle(ChatFormatting.GRAY));
            tellOther(source, target, "Shuffle aus");
            return 1;
        }
        String filter = modus == null ? "alle" : modus;
        String first = randomSong(filter, null);
        if (first == null) {
            source.sendFailure(Component.literal("Für diesen Modus gibt es keine Lieder."));
            return 0;
        }
        s.filter = filter;
        s.shuffle = true;
        target.sendSystemMessage(Component.literal("🔀 Shuffle an (" + filter + "). Weiter mit /weihnachtslied skip.")
                .withStyle(ChatFormatting.AQUA));
        play(target, first);
        tellOther(source, target, "Shuffle gestartet");
        return 1;
    }

    private static int stopSong(net.minecraft.commands.CommandSourceStack source, ServerPlayer target) {
        PLAYBACKS.remove(target.getUUID());
        stopAudio(target);
        SESSIONS.remove(target.getUUID());   // beendet auch Shuffle
        source.sendSuccess(() -> Component.literal("Musik für " + target.getName().getString() + " gestoppt.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static Path savePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("weihnachtsmod_adventskalender.json");
    }

    @Override
    public void onInitialize() {
        load();
        loadCustomSongs();

        // Begrüßung beim Beitreten
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!isChristmasSeason()) return;
            player.sendSystemMessage(Component.literal("🎄 Frohe Weihnachten, " + player.getName().getString() + "! 🎄")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            player.sendSystemMessage(countdownMessage());
            if (isAdventDay()) {
                player.sendSystemMessage(Component.literal("Dein Türchen wartet: /adventskalender")
                        .withStyle(ChatFormatting.GOLD));
            }
            player.sendSystemMessage(Component.literal("Lust auf Musik? /weihnachtslied")
                    .withStyle(ChatFormatting.YELLOW));
        });

        // Aufräumen, wenn ein Spieler geht
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            PLAYBACKS.remove(id);
            AUDIO.remove(id);
            SESSIONS.remove(id);
        });

        // Schneeflocken um jeden Spieler (alle 1 Sekunde)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0 || !isChristmasSeason()) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.SNOWFLAKE,
                            player.getX(), player.getY() + 2.5, player.getZ(),
                            25, 3.0, 1.0, 3.0, 0.02);
                }
            }
        });

        // Weihnachtslieder abspielen (jeden Tick)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<ServerPlayer> toAdvance = new ArrayList<>();   // Shuffle: Spieler, die ein neues Lied brauchen
            Iterator<Map.Entry<UUID, Playback>> it = PLAYBACKS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Playback> entry = it.next();
                Playback pb = entry.getValue();
                ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
                if (p == null) { it.remove(); continue; }   // Spieler offline
                if (pb.wait > 0) { pb.wait--; continue; }
                if (pb.index >= pb.pitches.length) {
                    it.remove();
                    if (session(p).shuffle) {
                        toAdvance.add(p);
                    } else {
                        p.sendSystemMessage(Component.literal("♪ Fertig! Nochmal? /weihnachtslied ♪")
                                .withStyle(ChatFormatting.GREEN));
                    }
                    continue;
                }
                float pitch = pb.pitches[pb.index];
                if (pitch > 0f) {
                    // nur dieser eine Spieler hört den Ton
                    p.playNotifySound(pb.song.instrument().value(), SoundSource.RECORDS, 1.5f, pitch);
                    if (p.level() instanceof ServerLevel level) {
                        level.sendParticles(ParticleTypes.NOTE,
                                p.getX(), p.getY() + 2.2, p.getZ(), 1, 0.3, 0.2, 0.3, 0.0);
                    }
                }
                pb.wait = Math.max(0, pb.durations[pb.index] * pb.song.ticksPerUnit() - 1);
                pb.index++;
            }

            // Aufnahmen: Restzeit herunterzählen
            for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
                Session sess = e.getValue();
                if (sess.audioTicksLeft > 0 && --sess.audioTicksLeft == 0) {
                    sess.audioTicksLeft = -1;
                    AUDIO.remove(e.getKey());
                    ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                    if (p == null) continue;
                    if (sess.shuffle) {
                        toAdvance.add(p);
                    } else {
                        p.sendSystemMessage(Component.literal("♪ Fertig! Nochmal? /weihnachtslied ♪")
                                .withStyle(ChatFormatting.GREEN));
                    }
                }
            }

            // Shuffle: nächstes zufälliges Lied
            for (ServerPlayer p : toAdvance) {
                Session sess = session(p);
                String next = randomSong(sess.filter, sess.current);
                if (next == null) { sess.shuffle = false; continue; }
                play(p, next);
            }
        });

        // Befehle
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            dispatcher.register(Commands.literal("weihnachten").executes(ctx -> {
                ctx.getSource().sendSuccess(WeihnachtsMod::countdownMessage, false);
                return 1;
            }));

            // /weihnachtslied                      -> zufälliges Lied für dich
            // /weihnachtslied <lied> [<spieler>]   -> Wunschlied aus der Liste, optional für einen Spieler
            // /weihnachtslied stop [<spieler>]     -> Musik stoppen
            // /weihnachtslied liste                -> alle Lieder anzeigen
            // /weihnachtslied skip [<spieler>]     -> nächstes Lied
            // /weihnachtslied shuffle [alle|noten|gesang] [<spieler>] -> Zufallswiedergabe an/aus
            dispatcher.register(Commands.literal("weihnachtslied")
                    .executes(ctx -> {
                        List<String> keys = new ArrayList<>(SONGS.keySet());
                        return startSong(ctx.getSource(), ctx.getSource().getPlayerOrException(),
                                keys.get(RANDOM.nextInt(keys.size())));
                    })
                    .then(Commands.literal("liste").executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("Lieder: "
                                + String.join(", ", SONGS.keySet())).withStyle(ChatFormatting.AQUA), false);
                        return 1;
                    }))
                    .then(Commands.literal("skip")
                            .executes(ctx -> skipSong(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                            .then(Commands.argument("spieler", EntityArgument.player())
                                    .executes(ctx -> skipSong(ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "spieler")))))
                    .then(Commands.literal("shuffle")
                            .executes(ctx -> shuffleSong(ctx.getSource(), ctx.getSource().getPlayerOrException(), null))
                            .then(Commands.argument("modus", StringArgumentType.word())
                                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("alle", "noten", "gesang"), builder))
                                    .executes(ctx -> shuffleSong(ctx.getSource(), ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "modus")))
                                    .then(Commands.argument("spieler", EntityArgument.player())
                                            .executes(ctx -> shuffleSong(ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "spieler"),
                                                    StringArgumentType.getString(ctx, "modus"))))))
                    .then(Commands.literal("stop")
                            .executes(ctx -> stopSong(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                            .then(Commands.argument("spieler", EntityArgument.player())
                                    .executes(ctx -> stopSong(ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "spieler")))))
                    .then(Commands.argument("lied", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SONGS.keySet(), builder))
                            .executes(ctx -> startSong(ctx.getSource(), ctx.getSource().getPlayerOrException(),
                                    StringArgumentType.getString(ctx, "lied")))
                            .then(Commands.argument("spieler", EntityArgument.player())
                                    .executes(ctx -> startSong(ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "spieler"),
                                            StringArgumentType.getString(ctx, "lied"))))));

            // /schneemann: 2 Schneeblöcke + geschnitzter Kürbis vor dir.
            // Der Kürbis wird zuletzt gesetzt, dadurch erzeugt Vanilla einen echten Schneegolem.
            // /rahmen unsichtbar|sichtbar [radius] -> Item Frames in der Nähe unsichtbar machen
            dispatcher.register(Commands.literal("rahmen")
                    .then(Commands.literal("unsichtbar")
                            .executes(ctx -> setFramesInvisible(ctx.getSource(),
                                    ctx.getSource().getPlayerOrException(), true, 5))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                    .executes(ctx -> setFramesInvisible(ctx.getSource(),
                                            ctx.getSource().getPlayerOrException(), true,
                                            IntegerArgumentType.getInteger(ctx, "radius")))))
                    .then(Commands.literal("sichtbar")
                            .executes(ctx -> setFramesInvisible(ctx.getSource(),
                                    ctx.getSource().getPlayerOrException(), false, 5))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                    .executes(ctx -> setFramesInvisible(ctx.getSource(),
                                            ctx.getSource().getPlayerOrException(), false,
                                            IntegerArgumentType.getInteger(ctx, "radius"))))));

            // /teller      -> unsichtbarer Item Frame mit Teller an deiner Position
            // /teller weg  -> entfernt den nächsten Teller
            dispatcher.register(Commands.literal("teller")
                    .executes(ctx -> runTellerFunction(ctx.getSource(), ctx.getSource().getPlayerOrException(),
                            "teller", "Teller gestellt. Lege das Essen mit Rechtsklick in den Rahmen."))
                    .then(Commands.literal("weg")
                            .executes(ctx -> runTellerFunction(ctx.getSource(), ctx.getSource().getPlayerOrException(),
                                    "teller_weg", "Teller entfernt."))));

            dispatcher.register(Commands.literal("schneemann").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                if (!(player.level() instanceof ServerLevel level)) return 0;

                long now = System.currentTimeMillis();
                Long last = snowmanCooldown.get(player.getStringUUID());
                if (last != null && now - last < SNOWMAN_COOLDOWN_MS) {
                    long rest = (SNOWMAN_COOLDOWN_MS - (now - last)) / 1000 + 1;
                    ctx.getSource().sendFailure(Component.literal("Bitte warte noch " + rest + " Sekunden. ⛄"));
                    return 0;
                }

                Direction facing = player.getDirection();
                BlockPos base = player.blockPosition().relative(facing, 2);
                BlockPos mid = base.above();
                BlockPos head = base.above(2);

                boolean freeSpace = level.getBlockState(base).isAir()
                        && level.getBlockState(mid).isAir()
                        && level.getBlockState(head).isAir()
                        && !level.getBlockState(base.below()).isAir();
                if (!freeSpace) {
                    ctx.getSource().sendFailure(Component.literal(
                            "Hier ist kein Platz für einen Schneemann. Such dir eine freie, ebene Fläche!"));
                    return 0;
                }

                snowmanCooldown.put(player.getStringUUID(), now);
                level.setBlock(base, Blocks.SNOW_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(mid, Blocks.SNOW_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(head, Blocks.CARVED_PUMPKIN.defaultBlockState()
                        .setValue(CarvedPumpkinBlock.FACING, facing.getOpposite()), Block.UPDATE_ALL);

                level.sendParticles(ParticleTypes.SNOWFLAKE,
                        base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5, 60, 0.5, 1.0, 0.5, 0.05);

                ctx.getSource().sendSuccess(() -> Component.literal("⛄ Ein Schneemann für dich!")
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD), false);
                return 1;
            }));

            dispatcher.register(Commands.literal("adventskalender").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                LocalDate today = LocalDate.now();

                if (!isAdventDay()) {
                    ctx.getSource().sendFailure(Component.literal(
                            "Der Adventskalender ist nur vom 1. bis 24. Dezember geöffnet!"));
                    return 0;
                }

                String key = today.getYear() + ":" + player.getStringUUID();
                Set<Integer> days = opened.computeIfAbsent(key, k -> new HashSet<>());
                int day = today.getDayOfMonth();

                if (!days.add(day)) {
                    ctx.getSource().sendFailure(Component.literal(
                            "Du hast Türchen " + day + " heute schon geöffnet. Komm morgen wieder! 🎁"));
                    return 0;
                }
                save();

                for (ItemStack stack : giftFor(day)) {
                    if (!player.getInventory().add(stack)) {
                        // Inventar voll: Item vor die Füße des Spielers legen
                        if (player.level() instanceof ServerLevel dropLevel) {
                            dropLevel.addFreshEntity(new ItemEntity(
                                    dropLevel, player.getX(), player.getY() + 0.5, player.getZ(), stack));
                        }
                    }
                }
                if (player.level() instanceof ServerLevel level) {
                    level.playSound((Player) null, player.blockPosition(),
                            SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 1.0f);
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                            player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.6, 0.8, 0.6, 0.1);
                }

                ctx.getSource().sendSuccess(() -> Component.literal("🎁 Türchen " + day + " geöffnet! Frohe Weihnachten!")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
                return 1;
            }));
        });
    }

    // ---------- Logik ----------

    private static boolean isAdventDay() {
        LocalDate t = LocalDate.now();
        return t.getMonthValue() == 12 && t.getDayOfMonth() <= 24;
    }

    /** 25. November bis 6. Januar */
    private static boolean isChristmasSeason() {
        LocalDate t = LocalDate.now();
        int m = t.getMonthValue(), d = t.getDayOfMonth();
        return (m == 11 && d >= 25) || m == 12 || (m == 1 && d <= 6);
    }

    private static Component countdownMessage() {
        LocalDate today = LocalDate.now();
        LocalDate xmas = LocalDate.of(today.getYear(), 12, 24);
        if (today.isAfter(LocalDate.of(today.getYear(), 12, 26))) {
            xmas = xmas.plusYears(1);
        }
        long days = ChronoUnit.DAYS.between(today, xmas);
        String text;
        if (days == 0) text = "🎅 Heute ist Heiligabend! Frohe Weihnachten!";
        else if (days < 0) text = "🎄 Frohe Weihnachten und ein gutes neues Jahr!";
        else text = "⏳ Noch " + days + (days == 1 ? " Tag" : " Tage") + " bis Heiligabend!";
        return Component.literal(text).withStyle(ChatFormatting.AQUA);
    }

    /** Macht alle Item Frames (auch leuchtende) im Umkreis unsichtbar oder wieder sichtbar. */
    private static int setFramesInvisible(CommandSourceStack source, ServerPlayer player, boolean invisible, int radius) {
        if (!(player.level() instanceof ServerLevel level)) return 0;
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, player.getBoundingBox().inflate(radius));
        for (ItemFrame frame : frames) {
            frame.setInvisible(invisible);
        }
        final int count = frames.size();
        final String text = count + " Rahmen" + (invisible ? " unsichtbar" : " wieder sichtbar")
                + " (Umkreis " + radius + " Blöcke).";
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
        return count;
    }

    /** Führt eine Teller-Funktion (data/weihnachtsmod/function/NAME.mcfunction) an der Position des Spielers aus. */
    private static int runTellerFunction(CommandSourceStack source, ServerPlayer player, String name, String text) {
        if (!(player.level() instanceof ServerLevel level)) return 0;
        MinecraftServer server = level.getServer();
        CommandSourceStack stack = server.createCommandSourceStack()
                .withLevel(level)
                .withPosition(player.position())
                .withSuppressedOutput();
        server.getCommands().performPrefixedCommand(stack, "function " + MOD_ID + ":" + name);
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static ItemStack named(ItemStack stack, String name, ChatFormatting color) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
        return stack;
    }

    private static List<ItemStack> giftFor(int day) {
        List<ItemStack> gifts = new ArrayList<>();

        if (day == 24) {
            gifts.add(named(new ItemStack(Items.DIAMOND, 5), "Heiligabend-Diamant", ChatFormatting.AQUA));
            gifts.add(named(new ItemStack(Items.CAKE, 1), "Weihnachtstorte", ChatFormatting.LIGHT_PURPLE));
            gifts.add(named(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1), "Goldener Weihnachtsapfel", ChatFormatting.GOLD));
            gifts.add(new ItemStack(Items.FIREWORK_ROCKET, 16));
            return gifts;
        }

        // Jeden Tag Plätzchen
        gifts.add(named(new ItemStack(Items.COOKIE, 8 + day), "Weihnachtsplätzchen", ChatFormatting.GOLD));

        // Dazu ein wechselnder Überraschungsgegenstand
        ItemStack[] surprises = {
                new ItemStack(Items.GOLDEN_CARROT, 8),
                new ItemStack(Items.PUMPKIN_PIE, 6),
                new ItemStack(Items.EMERALD, 3),
                new ItemStack(Items.SNOWBALL, 16),
                new ItemStack(Items.GOLD_INGOT, 4),
                new ItemStack(Items.COOKED_BEEF, 12),
                new ItemStack(Items.EXPERIENCE_BOTTLE, 6),
                new ItemStack(Items.DIAMOND, 1),
        };
        gifts.add(surprises[(day + RANDOM.nextInt(surprises.length)) % surprises.length]);

        // Kleine Chance auf Kohle (Streich vom Weihnachtsmann)
        if (RANDOM.nextInt(10) == 0) {
            gifts.add(named(new ItemStack(Items.COAL, 1), "Kohle vom Weihnachtsmann", ChatFormatting.DARK_GRAY));
        }
        return gifts;
    }

    // ---------- Speichern / Laden ----------

    private static void load() {
        Path p = savePath();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Map<String, Set<Integer>> data = GSON.fromJson(r, SAVE_TYPE);
            if (data != null) opened = data;
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + MOD_ID + "] Konnte Adventskalender-Daten nicht laden: " + e);
        }
    }

    private static void save() {
        try (Writer w = Files.newBufferedWriter(savePath())) {
            GSON.toJson(opened, SAVE_TYPE, w);
        } catch (IOException e) {
            System.err.println("[" + MOD_ID + "] Konnte Adventskalender-Daten nicht speichern: " + e);
        }
    }
}
