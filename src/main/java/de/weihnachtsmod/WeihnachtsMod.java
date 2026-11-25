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
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

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

    // ---------- /schnee ----------
    private static boolean snowParticles = false;                                   // Partikel an/aus
    private static final java.util.ArrayDeque<BlockPos> snowPending = new java.util.ArrayDeque<>();  // Spalten, die noch Schnee bekommen
    private static final java.util.ArrayDeque<Long> snowRemoving = new java.util.ArrayDeque<>();     // Schneeschichten, die noch entfernt werden
    private static final Set<Long> snowPlaced = new HashSet<>();                    // alle gesetzten Schneeschichten
    private static final Set<Long> snowKept = new HashSet<>();                      // beim Entfernen nicht geladen, bleiben vorgemerkt
    private static boolean snowLoaded = false;
    private static final Set<Long> snowConverted = new HashSet<>();                 // Erdbloecke unter dem Schnee, die zu Gras wurden
    private static final java.util.ArrayDeque<Long> snowRevert = new java.util.ArrayDeque<>();      // Erdbloecke, die wieder zu Erde werden
    private static final Set<Long> snowConvKept = new HashSet<>();                  // beim Zurueckwandeln nicht geladen
    private static final Set<Long> snowConvGrass = new HashSet<>();                 // Grasbloecke, die unter dickem Schnee zu Podsol wurden
    private static final java.util.ArrayDeque<Long> snowRevertGrass = new java.util.ArrayDeque<>();
    private static final Set<Long> snowConvGrassKept = new HashSet<>();
    private static final int SNOW_PER_TICK = 800;
    private static final int SNOW_REMOVE_PER_TICK = 600;

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

    /** Spielt einen Ton nur für diesen einen Spieler (an seiner Position). */
    private static void playTo(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.RECORDS,
                player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }

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
            playTo(target, SoundEvent.createVariableRangeEvent(id), 1.0f, 1.0f);
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
            if (true) return; // Dauer-Flocken aus, Flocken nur noch per /schnee
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.SNOWFLAKE,
                            player.getX(), player.getY() + 2.5, player.getZ(),
                            25, 3.0, 1.0, 3.0, 0.02);
                }
            }
        });

        // /schnee: Partikel in grossem Umkreis, Schnee setzen und entfernen
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerLevel level = server.overworld();
            if (snowParticles && server.getTickCount() % 40 == 0 && (level.isRaining() || level.isThundering())) {
                clearWeather(server);
            }
            if (snowParticles && server.getTickCount() % 4 == 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.level() == level) {
                        level.sendParticles(ParticleTypes.SNOWFLAKE,
                                player.getX(), player.getY() + 6, player.getZ(),
                                90, 14.0, 5.0, 14.0, 0.02);
                    }
                }
            }
            int placed = 0;
            while (placed < SNOW_PER_TICK && !snowPending.isEmpty()) {
                BlockPos column = snowPending.poll();
                placed++;
                placeSnowLayer(level, column.getX(), column.getZ());
            }
            if (placed > 0 && snowPending.isEmpty()) saveSnow();
            if (placed > 0 && server.getTickCount() % 200 == 0) saveSnow();
            int[] loadBudget = {2};   // pro Tick hoechstens 2 Chunks nachladen, damit der Server nicht ruckelt
            if (!snowRevert.isEmpty() || !snowRevertGrass.isEmpty()) {
                int reverted = 0;
                while (reverted < SNOW_REMOVE_PER_TICK && !snowRevert.isEmpty()) {
                    long packed = snowRevert.peek();
                    BlockPos pos = BlockPos.of(packed);
                    int st = ensureLoaded(level, pos, loadBudget);
                    if (st == 0) break;
                    snowRevert.poll();
                    reverted++;
                    if (st < 0) {
                        snowConvKept.add(packed);
                    } else if (level.getBlockState(pos).is(Blocks.GRASS_BLOCK) || level.getBlockState(pos).is(Blocks.PODZOL)) {
                        level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                while (reverted < SNOW_REMOVE_PER_TICK && !snowRevertGrass.isEmpty()) {
                    long packed = snowRevertGrass.peek();
                    BlockPos pos = BlockPos.of(packed);
                    int st = ensureLoaded(level, pos, loadBudget);
                    if (st == 0) break;
                    snowRevertGrass.poll();
                    reverted++;
                    if (st < 0) {
                        snowConvGrassKept.add(packed);
                    } else if (level.getBlockState(pos).is(Blocks.PODZOL)) {
                        level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                if (snowRevert.isEmpty() && snowRevertGrass.isEmpty()) {
                    snowConverted.addAll(snowConvKept);
                    snowConvKept.clear();
                    snowConvGrass.addAll(snowConvGrassKept);
                    snowConvGrassKept.clear();
                    saveSnow();
                }
            }
            if (!snowRemoving.isEmpty()) {
                int removed = 0;
                while (removed < SNOW_REMOVE_PER_TICK && !snowRemoving.isEmpty()) {
                    long packed = snowRemoving.peek();
                    BlockPos pos = BlockPos.of(packed);
                    int st = ensureLoaded(level, pos, loadBudget);
                    if (st == 0) break;
                    snowRemoving.poll();
                    removed++;
                    if (st < 0) {
                        snowKept.add(packed);
                    } else if (level.getBlockState(pos).is(Blocks.SNOW)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                if (snowRemoving.isEmpty()) {
                    snowPlaced.addAll(snowKept);
                    snowKept.clear();
                    saveSnow();
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
                    playTo(p, pb.song.instrument().value(), 1.5f, pitch);
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

            // /rahmen item | /rahmen leuchtend -> gibt dir einen Item Frame, der unsichtbar platziert wird
            dispatcher.register(Commands.literal("rahmen")
                    .then(Commands.literal("item").executes(ctx -> giveInvisibleFrame(ctx.getSource(), false)))
                    .then(Commands.literal("leuchtend").executes(ctx -> giveInvisibleFrame(ctx.getSource(), true))));

            // /zipfelmuetze [alle] -> Zipfelmütze für dich oder für alle Spieler, die online sind
            dispatcher.register(Commands.literal("zipfelmuetze")
                    .executes(ctx -> giveHat(ctx.getSource(), false))
                    .then(Commands.literal("alle").executes(ctx -> giveHat(ctx.getSource(), true))));

            // /weihnachtshilfe -> zeigt alle Befehle der Mod im Chat
            dispatcher.register(Commands.literal("weihnachtshilfe")
                    .executes(ctx -> showHelp(ctx.getSource())));

            // /teller      -> unsichtbarer Item Frame mit Teller an deiner Position
            // /teller weg  -> entfernt den nächsten Teller
            dispatcher.register(Commands.literal("teller")
                    .executes(ctx -> runTellerFunction(ctx.getSource(), ctx.getSource().getPlayerOrException(),
                            "teller", "Teller gestellt. Lege das Essen mit Rechtsklick in den Rahmen."))
                    .then(Commands.literal("weg")
                            .executes(ctx -> runTellerFunction(ctx.getSource(), ctx.getSource().getPlayerOrException(),
                                    "teller_weg", "Teller entfernt."))));

            // /schnee [radius] | /schnee stop | /schnee weg
            dispatcher.register(Commands.literal("schnee")
                    .executes(ctx -> startSnow(ctx.getSource(), 48))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(8, 300))
                            .executes(ctx -> startSnow(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))
                    .then(Commands.literal("stop").executes(ctx -> stopSnow(ctx.getSource())))
                    .then(Commands.literal("weg")
                            .executes(ctx -> removeSnow(ctx.getSource(), 300))
                            .then(Commands.literal("alle").executes(ctx -> removeSnow(ctx.getSource(), -1)))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(8, 1000))
                                    .executes(ctx -> removeSnow(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))));

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

    // ---------- /schnee: Hilfsmethoden ----------

    private static Path snowFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("weihnachtsmod_schnee.json");
    }

    /** Liest gespeicherte Schneeschichten (einmal pro Serverstart), damit /schnee weg auch nach einem Neustart klappt. */
    private static void loadSnow() {
        if (snowLoaded) return;
        snowLoaded = true;
        Path file = snowFile();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            Gson gson = new Gson();
            JsonObject obj = gson.fromJson(reader, JsonObject.class);
            if (obj != null && obj.has("positions")) {
                long[] arr = gson.fromJson(obj.get("positions"), long[].class);
                for (long l : arr) snowPlaced.add(l);
            }
            if (obj != null && obj.has("converted")) {
                long[] conv = gson.fromJson(obj.get("converted"), long[].class);
                for (long l : conv) snowConverted.add(l);
            }
            if (obj != null && obj.has("convertedGrass")) {
                long[] conv = gson.fromJson(obj.get("convertedGrass"), long[].class);
                for (long l : conv) snowConvGrass.add(l);
            }
        } catch (Exception e) {
            System.err.println("[Weihnachtsmod] Konnte " + file + " nicht lesen: " + e);
        }
    }

    private static void saveSnow() {
        try {
            long[] arr = new long[snowPlaced.size()];
            int i = 0;
            for (long l : snowPlaced) arr[i++] = l;
            Gson gson = new Gson();
            JsonObject obj = new JsonObject();
            obj.add("positions", gson.toJsonTree(arr));
            long[] conv = new long[snowConverted.size()];
            int j = 0;
            for (long l : snowConverted) conv[j++] = l;
            obj.add("converted", gson.toJsonTree(conv));
            long[] convGrass = new long[snowConvGrass.size()];
            int k = 0;
            for (long l : snowConvGrass) convGrass[k++] = l;
            obj.add("convertedGrass", gson.toJsonTree(convGrass));
            Files.writeString(snowFile(), gson.toJson(obj));
        } catch (Exception e) {
            System.err.println("[Weihnachtsmod] Konnte Schnee-Datei nicht speichern: " + e);
        }
    }

    /** Setzt in dieser Spalte eine Schneeschicht (1 bis 3 hoch), wenn Schnee dort halten kann. */
    private static void placeSnowLayer(ServerLevel level, int x, int z) {
        if (!level.hasChunkAt(new BlockPos(x, 64, z))) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);   // zaehlt Blaetter mit, Schnee liegt also auch auf Baeumen
        BlockPos pos = new BlockPos(x, y, z);
        BlockState here = level.getBlockState(pos);
        if (!(here.isAir() || here.canBeReplaced()) || !here.getFluidState().isEmpty()) return;
        BlockState snow = Blocks.SNOW.defaultBlockState();
        boolean onLeaves = level.getBlockState(pos.below()).is(BlockTags.LEAVES);
        if (!snow.canSurvive(level, pos) && !onLeaves) return;
        // Wellen: mehrere Sinuswellen ergeben weiche Huegel und Taeler, dazu ein kleines Zufallsrauschen
        double wave = Math.sin(x * 0.30) + Math.sin(z * 0.25) + Math.sin((x + z) * 0.17) + Math.sin((x - z) * 0.11);
        double n = (wave + 4.0) / 8.0 + (level.getRandom().nextDouble() - 0.5) * 0.15;
        int layers = n < 0.40 ? 1 : (n < 0.60 ? 2 : 3);
        // Boden unter dem Schnee: 1 Schicht -> Erde wird Gras, ab 2 Schichten wuerde Gras zu Erde absterben,
        // darum wird der Boden dann zu Podsol (stirbt nicht ab und zeigt den weissen Schneerand)
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        boolean dirtLike = belowState.is(Blocks.DIRT) || belowState.is(Blocks.COARSE_DIRT) || belowState.is(Blocks.ROOTED_DIRT);
        boolean grassBlock = belowState.is(Blocks.GRASS_BLOCK);
        if (layers == 1) {
            if (dirtLike) {
                level.setBlock(below, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                snowConverted.add(below.asLong());
            }
        } else if (dirtLike) {
            level.setBlock(below, Blocks.PODZOL.defaultBlockState(), Block.UPDATE_ALL);
            snowConverted.add(below.asLong());
        } else if (grassBlock) {
            level.setBlock(below, Blocks.PODZOL.defaultBlockState(), Block.UPDATE_ALL);
            snowConvGrass.add(below.asLong());
        }
        level.setBlock(pos, snow.setValue(BlockStateProperties.LAYERS, layers), onLeaves ? Block.UPDATE_CLIENTS : Block.UPDATE_ALL);
        snowPlaced.add(pos.asLong());
    }

    /** Beendet Regen und Gewitter (Wetter auf klar), damit nur Schnee faellt. */
    private static void clearWeather(MinecraftServer server) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "weather clear");
    }

    private static int startSnow(CommandSourceStack source, int radius) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!(player.level() instanceof ServerLevel level)) return 0;
        MinecraftServer server = level.getServer();
        if (level != server.overworld()) {
            source.sendFailure(Component.literal("Schnee gibt es nur in der Oberwelt."));
            return 0;
        }
        loadSnow();
        snowParticles = true;
        if (level.isRaining() || level.isThundering()) clearWeather(server);
        final int cx = player.getBlockX();
        final int cz = player.getBlockZ();
        List<BlockPos> columns = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) columns.add(new BlockPos(cx + dx, 0, cz + dz));
            }
        }
        columns.sort(java.util.Comparator.comparingInt(
                b -> (b.getX() - cx) * (b.getX() - cx) + (b.getZ() - cz) * (b.getZ() - cz)));
        snowPending.addAll(columns);
        source.sendSuccess(() -> Component.literal("❄ Es schneit! Schnee im Umkreis von " + radius
                + " Blöcken, auch auf Blättern. Regen wird beendet. /schnee stop beendet die Flocken, /schnee weg räumt den Schnee weg.")
                .withStyle(ChatFormatting.WHITE), true);
        return 1;
    }

    private static int stopSnow(CommandSourceStack source) {
        snowParticles = false;
        snowPending.clear();
        source.sendSuccess(() -> Component.literal("Die Schneeflocken hören auf. Der Schnee am Boden bleibt, /schnee weg räumt ihn weg.")
                .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /** Laedt den Chunk bei Bedarf nach. 1 = geladen, 0 = diesen Tick kein Budget mehr, -1 = nicht ladbar. */
    private static int ensureLoaded(ServerLevel level, BlockPos pos, int[] budget) {
        if (level.hasChunkAt(pos)) return 1;
        if (budget[0] <= 0) return 0;
        budget[0]--;
        try {
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        } catch (Exception e) {
            return -1;
        }
        return level.hasChunkAt(pos) ? 1 : -1;
    }

    private static long chunkKey(long packed) {
        BlockPos p = BlockPos.of(packed);
        return ((long) (p.getX() >> 4) << 32) ^ ((p.getZ() >> 4) & 0xffffffffL);
    }

    /** Nimmt alle Positionen im Umkreis aus der Menge und gibt sie nach Chunk sortiert zurueck (radius < 0 = alle). */
    private static List<Long> takeInRadius(Set<Long> set, int cx, int cz, int radius) {
        List<Long> taken = new ArrayList<>();
        long r2 = (long) radius * radius;
        for (long l : set) {
            if (radius < 0) { taken.add(l); continue; }
            BlockPos p = BlockPos.of(l);
            long dx = p.getX() - cx;
            long dz = p.getZ() - cz;
            if (dx * dx + dz * dz <= r2) taken.add(l);
        }
        set.removeAll(taken);
        taken.sort(java.util.Comparator.comparingLong(WeihnachtsMod::chunkKey));
        return taken;
    }

    /** /schnee weg [radius] oder /schnee weg alle (radius = -1). Entfernt den Schnee der Mod im Umkreis, auch in nicht geladenen Bereichen. */
    private static int removeSnow(CommandSourceStack source, int radius) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        loadSnow();
        snowPending.clear();
        final int cx = player.getBlockX();
        final int cz = player.getBlockZ();
        List<Long> layers = takeInRadius(snowPlaced, cx, cz, radius);
        snowRemoving.addAll(layers);
        snowRevert.addAll(takeInRadius(snowConverted, cx, cz, radius));
        snowRevertGrass.addAll(takeInRadius(snowConvGrass, cx, cz, radius));
        final int count = layers.size();
        final String where = radius < 0 ? "überall" : "im Umkreis von " + radius + " Blöcken";
        source.sendSuccess(() -> Component.literal("Der Schnee wird " + where + " weggeräumt (" + count
                + " Schichten). Entfernte Bereiche werden dafür kurz nachgeladen, das dauert ein paar Sekunden.")
                .withStyle(ChatFormatting.AQUA), true);
        return count;
    }

    /** Gibt dem Spieler einen Item Frame, der beim Platzieren unsichtbar ist. */
    private static int giveInvisibleFrame(CommandSourceStack source, boolean glow) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!(player.level() instanceof ServerLevel level)) return 0;
        MinecraftServer server = level.getServer();
        String id = glow ? "glow_item_frame" : "item_frame";
        CommandSourceStack stack = server.createCommandSourceStack().withEntity(player).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(stack,
                "give @s " + id + "[entity_data={id:\"minecraft:" + id + "\",Invisible:1b}]");
        source.sendSuccess(() -> Component.literal("Du hast einen unsichtbaren Rahmen bekommen. "
                + "Lege ein Item hinein, sonst siehst du ihn gar nicht.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** Setzt eine Zipfelmütze auf den Kopf (nur wenn dort noch nichts getragen wird). Sie ist ein Papier mit eigenem Modell im Resource Pack. */
    private static int giveHat(CommandSourceStack source, boolean all) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer self = source.getPlayerOrException();
        if (!(self.level() instanceof ServerLevel level)) return 0;
        MinecraftServer server = level.getServer();
        List<ServerPlayer> targets = all ? new ArrayList<>(server.getPlayerList().getPlayers()) : List.of(self);
        int given = 0;
        int skipped = 0;
        for (ServerPlayer target : targets) {
            if (!target.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                skipped++;
                continue;
            }
            CommandSourceStack stack = server.createCommandSourceStack().withEntity(target).withSuppressedOutput();
            server.getCommands().performPrefixedCommand(stack,
                    "item replace entity @s armor.head with minecraft:paper[item_model=\"weihnachtsmod:zipfelmuetze\",equippable={slot:\"head\"}]");
            given++;
        }
        final int g = given;
        final int sk = skipped;
        source.sendSuccess(() -> Component.literal("🎅 " + g + " Zipfelmütze(n) verteilt"
                + (sk > 0 ? ", " + sk + " Spieler tragen schon etwas auf dem Kopf." : "."))
                .withStyle(ChatFormatting.RED), true);
        return given;
    }

    private static void helpLine(CommandSourceStack source, String command, String text) {
        source.sendSuccess(() -> Component.literal(command).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("  " + text).withStyle(ChatFormatting.GRAY)), false);
    }

    private static void helpTitle(CommandSourceStack source, String title) {
        source.sendSuccess(() -> Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
    }

    /** Zeigt alle Befehle der Weihnachtsmod. */
    private static int showHelp(CommandSourceStack source) {
        helpTitle(source, "Weihnachtsmod: alle Befehle");
        helpTitle(source, "Allgemein");
        helpLine(source, "/weihnachten", "Countdown bis Heiligabend");
        helpLine(source, "/adventskalender", "Tägliches Türchen (1. bis 24. Dezember)");
        helpLine(source, "/schneemann", "Baut vor dir einen Schneemann");
        helpTitle(source, "Schnee");
        helpLine(source, "/schnee [radius]", "Schneeflocken und Schnee in Wellen, Radius 8 bis 300, beendet Regen");
        helpLine(source, "/schnee stop", "Beendet die Flocken, der Schnee bleibt");
        helpLine(source, "/schnee weg [radius|alle]", "Räumt den gesetzten Schnee weg (Standard 300 Blöcke)");
        helpTitle(source, "Rahmen und Teller");
        helpLine(source, "/rahmen item", "Gibt dir einen unsichtbaren Item Frame");
        helpLine(source, "/rahmen leuchtend", "Gibt dir einen unsichtbaren leuchtenden Rahmen");
        helpLine(source, "/rahmen unsichtbar [radius]", "Macht Rahmen in der Nähe unsichtbar");
        helpLine(source, "/rahmen sichtbar [radius]", "Macht sie wieder sichtbar");
        helpLine(source, "/teller", "Stellt Rahmen mit Teller an deine Position");
        helpLine(source, "/teller weg", "Entfernt den nächsten Teller");
        helpTitle(source, "Zipfelmütze");
        helpLine(source, "/zipfelmuetze", "Setzt dir eine Zipfelmütze auf");
        helpLine(source, "/zipfelmuetze alle", "Setzt allen Spielern, die online sind, eine Zipfelmütze auf");
        helpTitle(source, "Weihnachtslieder");
        helpLine(source, "/weihnachtslied", "Zufälliges Lied");
        helpLine(source, "/weihnachtslied <lied> [spieler]", "Bestimmtes Lied, optional für einen Spieler");
        helpLine(source, "/weihnachtslied liste", "Alle Lieder anzeigen");
        helpLine(source, "/weihnachtslied skip [spieler]", "Nächstes Lied");
        helpLine(source, "/weihnachtslied shuffle [alle|noten|gesang] [spieler]", "Zufallswiedergabe an oder aus");
        helpLine(source, "/weihnachtslied stop [spieler]", "Musik stoppen");
        helpTitle(source, "Hilfe");
        helpLine(source, "/weihnachtshilfe", "Zeigt diese Liste");
        return 1;
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
