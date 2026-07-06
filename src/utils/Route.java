import java.util.List;

public class Route {
    public String path;
    public String root;
    public String default_file;
    public List<String> methods;
    public Boolean directory_listing;
    public Long client_body_limit;
    public List<String> cgi_extensions;
    public String redirect;
}