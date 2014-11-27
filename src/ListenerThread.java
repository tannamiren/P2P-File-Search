import java.io.*;
import java.net.Socket;

public class ListenerThread extends Thread {
    private final Socket _client;
    private InputStream _instream;
    private OutputStream _outstream;
    private Connector _connector_ref;
    private boolean _running = true;
    public ListenerThread(Socket client, Connector connector) {
        _connector_ref = connector;
        _client = client;
        try {
            _instream = _client.getInputStream();
            _outstream = _client.getOutputStream();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void cleanup() {
        terminate();
        try {
            _instream.close();
            _outstream.close();
            _client.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void terminate() {
        _running = false;
    }

    @Override
    public void run() {
        while(_running) {
            try {
                ObjectInputStream obj_in = new ObjectInputStream(_instream);
                Message msg = (Message) obj_in.readObject();
                /*
                BYE PROTOCOL:
                 when a bye message is received,
                    send a bye_ack message to sender,
                    termincate the current listener thread,
                    remove the sender of bye from _node_loookup

                 when a bye_ack is received:
                    terminate the listener thread.
                    remove the sender of bye from _node_loookup
                  */
                if(msg.getType().equals("bye")) {
                    Message bye_ack = new Message.MessageBuilder()
                        .from(_connector_ref._node_ref._info)
                        .type("bye_ack")
                        .build();
                    _connector_ref.send_message(bye_ack,msg.getSender());
                    obj_in.close();
                    terminate();

                    _connector_ref._node_lookup.remove(msg.getSender().toString());
                }
                else if (msg.getType().equals("bye_ack")) {
                    obj_in.close();
                    terminate();
                    _connector_ref._node_lookup.remove(msg.getSender().toString());
                }
                /*
                Only bye protocol is handled inside the listener thread,
                other messages are passed on to the connector.
                 */
                else {
                    _connector_ref.deliver_msg(msg);
                }
            } catch(IOException | ClassNotFoundException ex) {
                System.out.println("StreamException");
                terminate();
            }
        }
    }
}
