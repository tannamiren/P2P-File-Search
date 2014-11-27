import java.io.*;
import java.net.Socket;

public class Downloader extends Thread {

    private String _filename;
    private int _server_port;
    private String _server_ip;
    private String _metafile = "metafile";

    public Downloader(String filename, String ip, int port) {
        _filename = filename;
        _server_ip = ip;
        _server_port = port;
    }

    public void copyStream(InputStream input, OutputStream output)
            throws IOException {
        byte[] buffer = new byte[1024]; // Adjust if you want
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }

    @Override
    public void run() {
        try {
            Socket socket = new Socket(_server_ip,_server_port);

            OutputStream outstream = socket.getOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outstream,"UTF-8");
            writer.write(_filename);
            writer.write("\r\n");
            writer.flush();

            InputStreamReader sin = new InputStreamReader(socket.getInputStream());
            BufferedReader read = new BufferedReader(sin);
            String metadata = System.lineSeparator() + _filename + " " + read.readLine().trim() + System.lineSeparator();

            FileOutputStream metaout = new FileOutputStream(_metafile,true);
            byte metadata_content[] = metadata.getBytes();
            metaout.write(metadata_content);
            metaout.close();

            InputStream is = socket.getInputStream();
            FileOutputStream fos = new FileOutputStream(_filename);
            copyStream(is,fos);

            fos.close();
            socket.close();
        } catch(IOException ex) {}
    }
}

