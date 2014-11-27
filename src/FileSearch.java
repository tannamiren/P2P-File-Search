import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileSearch {
    private String _filename;
    public FileSearch(String filename) {
        _filename = filename;
    }

    public synchronized String get_metadata(String filename) {
        String line;
        String metadata = "";
        try(BufferedReader reader = new BufferedReader(new FileReader(_filename))) {
            while((line = reader.readLine()) != null) {
                if(search_line(line, filename)) {
                    metadata = line.split(" ")[1];
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return metadata;
    }

    public synchronized boolean search_line(String line, String query) {
        List<String> search_arr = Arrays.asList(line.split("(,)|( )"));
        if(search_arr.contains(query)) {
            return true;
        }
        else {
            return false;
        }
    }

    public synchronized ArrayList search(String query) {
        ArrayList<String> results = new ArrayList<>();
        try(BufferedReader reader = new BufferedReader(new FileReader(_filename))) {
            String line;
            String matched_file;
            while((line = reader.readLine()) != null) {
                if(search_line(line, query)) {
                    matched_file = line.split(" ")[0];
                    results.add(matched_file);
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return results;
    }

    public static void main(String args[]) {
        FileSearch f = new FileSearch(args[0]);
        System.out.println(f.search(args[1]));
    }
}
