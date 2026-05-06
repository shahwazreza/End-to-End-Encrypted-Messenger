import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MessageHistory {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm").withZone(ZoneId.systemDefault());

    public record Entry(long timestamp, boolean sent, String text) {
        public String formattedTime() {
            return TIME_FMT.format(Instant.ofEpochMilli(timestamp));
        }
    }

    public static void append(String username, String peer, boolean sent, String text) {
        try {
            Path file = historyFile(username, peer);
            Files.createDirectories(file.getParent());
            String line = System.currentTimeMillis() + "|" + (sent ? "S" : "R") + "|"
                    + text.replace("\n", "\\n") + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    public static List<Entry> load(String username, String peer) {
        Path file = historyFile(username, peer);
        if (!Files.exists(file)) return List.of();
        try {
            List<Entry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) continue;
                try {
                    long ts   = Long.parseLong(parts[0]);
                    boolean s = "S".equals(parts[1]);
                    String t  = parts[2].replace("\\n", "\n");
                    entries.add(new Entry(ts, s, t));
                } catch (NumberFormatException ignored) {}
            }
            return entries;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path historyFile(String username, String peer) {
        return Paths.get(System.getProperty("user.home"), ".messenger",
                username, "history", peer.toLowerCase() + ".log");
    }
}
