import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

// Use the same java.util.Properties + UTF-8 reader semantics as Minecraft.
public class ReadProperties {
    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (String key : new String[]{"level-name", "level-seed"}) {
            String value = properties.getProperty(key, key.equals("level-name") ? "world" : "");
            System.out.println(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
