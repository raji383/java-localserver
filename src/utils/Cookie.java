public class Cookie {
    private final String name;
    private final String value;

    public Cookie(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String toHeader() {
        return name + "=" + value + "; Path=/; HttpOnly";
    }
}
