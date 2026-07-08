import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private static final long DEFAULT_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SessionManager() {
        this.ttlMillis = DEFAULT_TTL_MILLIS;
    }

    public static final SessionManager INSTANCE = new SessionManager();

    public SessionManager(long ttlMillis) {
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
    }

    public synchronized SessionResult getOrCreate(String sessionId) {
        cleanupExpired();
        if (sessionId != null && !sessionId.isBlank()) {
            Session s = sessions.get(sessionId);
            if (s != null) {
                s.touch();
                return new SessionResult(s, false);
            }
        }
        Session created = new Session();
        sessions.put(created.getId(), created);
        return new SessionResult(created, true);
    }

    public void cleanupExpired() {
        long now = Instant.now().toEpochMilli();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Session> e = it.next();
            Session s = e.getValue();
            if (now - s.getLastAccessMillis() > ttlMillis) {
                it.remove();
            }
        }
    }

    public static final class SessionResult {
        public final Session session;
        public final boolean created;

        SessionResult(Session session, boolean created) {
            this.session = session;
            this.created = created;
        }
    }
}
