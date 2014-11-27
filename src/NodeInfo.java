import java.io.Serializable;

public class NodeInfo implements Serializable {
    public final int port;
    public final String ip;
    public NodeInfo(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }
    /*
    alternative constructor for
    input like "127.0.0.1:8000"
     */
    public NodeInfo(String ip_port) {
        String[] parts = ip_port.split(":");
        this.ip = parts[0];
        this.port = Integer.parseInt(parts[1]);
    }

    @Override public String toString() {
        return String.format("%s:%s",ip,port);
    }
}
