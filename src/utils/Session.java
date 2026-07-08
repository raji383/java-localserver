import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Session {
    private final String id;
    private final Map<String, String> data = new ConcurrentHashMap<>();
    private volatile long lastAccessMillis;

    public Session() {
        this.id = UUID.randomUUID().toString();
        this.touch();
    }

    public String getId() {
        return id;
    }

    public Map<String, String> getData() {
        return data;
    }

    public long getLastAccessMillis() {
        return lastAccessMillis;
    }

    public void touch() {
        this.lastAccessMillis = Instant.now().toEpochMilli();
    }
}
