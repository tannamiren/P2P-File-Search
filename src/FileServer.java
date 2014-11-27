import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Created by swair on 7/25/14.
 */
public class FileServer extends Thread {
    public Node _node_ref;
    public FileServer(Node n) {
        _node_ref = n;
    }
    @Override
    public void run() {
        try (ServerSocket server = new ServerSocket(_node_ref._fileserver_port)) {
            while(true) {
                Socket client = server.accept();
                FileTransfer transfer_thread = new FileTransfer(client,this);
                transfer_thread.start();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
