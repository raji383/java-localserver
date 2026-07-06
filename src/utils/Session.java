import java.util.UUID;

public class Session {
    private final String id;

    public Session() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }
}
