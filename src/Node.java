import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Node extends Thread {
    public final NodeInfo _info;
    public Connector _connector;
    public String _datafile = "metafile";
    private SearchKeeper _search_keeper;
    public int _node_id;
    public int _fileserver_port;
    private boolean _am_i_leaving = false;
    private List<String> _leave_acks;
    private ConcurrentHashMap<String, Long> _start_times;

    public List<String> _hop_log = Collections.synchronizedList(new ArrayList<String>());
    public List<String> _time_log = Collections.synchronizedList(new ArrayList<String>());

    /*
    _search_agents stores UUID(strings) : SearchAgent object
    for that search
     */
    public ConcurrentHashMap<String, SearchAgent> _search_agents;

    public Node(NodeInfo info) {
        _info = info;
        _connector = new Connector(this);
        _connector.start();
        _search_agents = new ConcurrentHashMap<>();
        _search_keeper = new SearchKeeper(this);
        _search_keeper.start();
        _leave_acks = new ArrayList<>();
        _start_times = new ConcurrentHashMap<>();
        _fileserver_port = info.port + 3000;
    }

    public void set_id(int id) {
        _node_id = id;
    }

    private void reset_logs() {
        try {
            Writer log_writer = new PrintWriter(new FileWriter("query_hop.log"));
            log_writer.append("");
            log_writer.close();
        } catch(IOException ex) {
            ex.printStackTrace();
        }
        try {
            Writer log_writer = new PrintWriter(new FileWriter("time.log"));
            log_writer.append("");
            log_writer.close();
        } catch(IOException ex) {
            ex.printStackTrace();
        }
    }

    private void write_hoplog() {
        try {
            Writer log_writer = new PrintWriter(new FileWriter("query_hop.log",true));
            for(String line : _hop_log) {
                log_writer.append(line);
            }
            log_writer.close();
        } catch(IOException ex) {
            ex.printStackTrace();
        }
        _hop_log.clear();
    }

    private void write_timelog() {
        try {
            Writer log_writer = new PrintWriter(new FileWriter("time.log",true));
            for(String line : _time_log) {
                log_writer.append(line);
            }
            log_writer.close();
        } catch(IOException ex) {
            ex.printStackTrace();
        }
        _time_log.clear();
    }

    public synchronized void take_commands() {
        Scanner scanner = new Scanner(System.in);
        while(true) {
            System.out.print("> ");
            String command = scanner.nextLine();
            this.execute_command(command);
        }
    }

    public void process_msg(Message msg) {
        System.out.println("got msg "+ msg.getType() + " from " + msg.getSender());

        if (msg.getType().equals("search")) {
            @SuppressWarnings("unchecked") HashMap<String, String> content = msg.getContent();
            System.out.println(content);
            if (_search_keeper.has(content.get("search_id"))) {
                /*
                then I've already searched for this message
                in 'recent' past, won't do a local search
                 */
            }
            else {
                _search_keeper.add(content.get("search_id"),msg.getSender());

                String result = local_search(content.get("search_term"));

                if (result.equals("")) {
                    // Don't send a reply
                }
                else {
                /*
                now build a search_result
                message and send to msg.getSender()
                 */

                HashMap<String, String> res_content = new HashMap<>();

                res_content.put("search_id", content.get("search_id"));
                res_content.put("search_term", content.get("search_term"));
                res_content.put("initiator", content.get("initiator"));
                res_content.put("search_result", result);

                Message result_msg = new Message.MessageBuilder()
                        .type("search_result")
                        .content(res_content)
                        .to(msg.getSender())
                        .from(_info).build();

                System.out.println("sending search_results "+ result);
                _connector.send_message(result_msg, msg.getSender());
                }
            }

             /*
             forward msg to neighbours is hop_count != 1
             */
            if (Integer.parseInt(content.get("hop_count")) <= 1) {
                /* Don't forward the message */
            }
            else {
                // Decrease the hop_count of the new message
                int fwd_hop_count = Integer.parseInt(content.get("hop_count")) - 1;
                HashMap<String, String> fwd_content = new HashMap<>();

                fwd_content.put("search_id", content.get("search_id"));
                fwd_content.put("search_term", content.get("search_term"));
                fwd_content.put("initiator", content.get("initiator"));
                fwd_content.put("hop_count", Integer.toString(fwd_hop_count));

                Message fwd_msg = new Message.MessageBuilder()
                        .type("search")
                        .content(fwd_content)
                        .from(_info).build();

                _connector.send_neighbours_except(fwd_msg, msg.getSender());
            }

        } // end *search* handling

        /*
         *search_result* handling
        */
        else if(msg.getType().equals("search_result")) {
            @SuppressWarnings("unchecked")
            HashMap<String, String> content = msg.getContent();

            /*
            If the search is initiated by us,
            we'll have an associated search agent in _search_agents,
            since we got a result, stop the agent.
             */
            String search_id = content.get("search_id");
            //System.out.println("id:peers : " + _search_keeper._search_peers);
            if (_search_agents.containsKey(search_id)) {
                SearchAgent sa = _search_agents.get(search_id);
                System.out.println("result received: " +
                        sa._search_term + " ," +
                        content.get("search_result") + " " + msg.getSender());

                long end_time = System.currentTimeMillis();
                long diff = end_time - sa._start_time;
                String logline = String.format("%-20s %-12s %-12s\n", sa._search_id, sa._search_term, diff);
                _time_log.add(logline);

                sa.terminate();
                //TODO: display results properly
            }
            /*
            this search could be a relay-search
            check _search_keeper for its id
            if its there, forward to the peer,
             */
            else if (_search_keeper.has(search_id)) {
                //System.out.println("got a relayed search result from " + msg.getSender());
                if (_search_keeper.has_peer_for(search_id)) {
                    _connector.send_message(msg, _search_keeper.get_peer(search_id));
                }
                else {
                    System.err.println("id exists but no peer for "+ search_id + " " + content.get("search_term") );
                }
            }
            /*
            else we may have timed out on it, so drop it
             */
            else {
               // do nothing
            }
        } //end *search_result* handling

        else if (msg.getType().equals("bye")) {
            /*
            when we get a "bye" message from a node,
            that node is exiting, bye is the last message.
            So remove that node from Connector.
             */
            Message bye_msg = new Message.MessageBuilder()
                .from(_info)
                .type("bye")
                .build();
            _connector.send_message(bye_msg,msg.getSender());
            _connector.remove_neighbour(msg.getSender());
        }

        else if (msg.getType().equals("can_i_leave")) {
            @SuppressWarnings("unchecked")
            HashMap<String, String> content = msg.getContent();
            int their_id = Integer.parseInt(content.get("node_id"));

            /*
            say no, if we are trying to leave AND
            our node_id is smaller than theirs.
             */
            if(_am_i_leaving && their_id > _node_id) {
                Message reply_msg = new Message.MessageBuilder()
                    .type("no_you_cannot")
                    .from(_info)
                    .to(msg.getSender())
                    .build();
                _connector.send_message(reply_msg,msg.getSender());
            }
            else {
                Message reply_msg = new Message.MessageBuilder()
                    .type("yes_you_can")
                    .to(msg.getSender())
                    .from(_info).build();
                _connector.send_message(reply_msg,msg.getSender());
            }
        }

        else if (msg.getType().equals("yes_you_can")) {
            _leave_acks.add(msg.getSender().toString());
        }

        else if (msg.getType().equals("no_you_cannot")) {

        }

        else if (msg.getType().equals("neighbours_data")) {
            @SuppressWarnings("unchecked")
            HashMap<String, String> content = msg.getContent();

            String neighbours = content.get("neighbours");
            String nodes[] = neighbours.split(",");

            for(String n : nodes) {
                if (!n.equals(_info.toString())) {
                    if(!_connector._node_lookup.containsKey(n)) {
                       _connector.join_neighbour(new NodeInfo(n));
                    }
                }
            }
        }

    }

    public String local_search(String query) {
        FileSearch file_search = new FileSearch(_datafile);
        @SuppressWarnings("unchecked")
        ArrayList<String> results = file_search.search(query);
        if (results.isEmpty()) {
            return "";
        }
        else {
            String result_str = "";
            for (String s : results) {
                result_str += s + ",";
            }
            return result_str;
        }

    }

    public synchronized void execute_command(String command) {
        String cmd = command.toLowerCase();
        String help_msg = String.format("\n%s\n" +
                        "%-20s\t:\t%s\n" +
                        "%-20s\t:\t%s\n" +
                        "%-20s\t:\t%s\n" +
                        "%-20s\t:\t%s\n" +
                        "%-20s\t:\t%s\n",
                "Following Commands are supported:",
                "help","Prints this help",
                "fetch <filename> <ip>:<port>", "Fetches file from <ip>",
                "join <ip>:<port>","Join the cluser of node with <ip> listening on <port>",
                "leave","leave from cluster(s)",
                "search <keywords>","search for a file");

        if      (cmd.startsWith("help")) {
            System.out.println(help_msg);
        }
        else if (cmd.startsWith("join")) {
            if (!cmd.contains(" ") | !cmd.contains(":")) {
                System.out.println("Incorrect Usage of Join command!");
                System.out.println(help_msg);
                return;
            }
            String[] parts = cmd.split(" ");
            if(parts.length != 2) {
                System.out.println("Incorrect Usage of Join command!");
                System.out.println(help_msg);
            }
            else {
                _connector.join_neighbour(new NodeInfo(parts[1]));
            }
        }
        else if (cmd.startsWith("search")) {
            String[] parts = cmd.split(" ");
            String search_term = parts[1];
            SearchAgent search_agent = new SearchAgent(search_term,this);
            _search_agents.put(search_agent._search_id.toString(), search_agent);

            long start_time = System.currentTimeMillis();
            _start_times.put(search_agent._search_id.toString(),start_time);

            search_agent.start();
        }
        else if(cmd.equals("nodes")) {
            System.out.println(_connector._node_lookup.keySet());
        }
        else if(cmd.equals("bye")) {
            say_bye();
        }
        else if(cmd.equals("leave")) {
            /*
            if node doesn't have any neighbours,
            just return
             */
            if (_connector._node_lookup.isEmpty()) {
                return;
            }
            /*
            start leaving protocol,
            set _am_i_leaving as true;
             */
            _am_i_leaving = true;

            HashMap<String, String> payload = new HashMap<>();
            payload.put("node_id",Integer.toString(_node_id));
            Message leave_msg = new Message.MessageBuilder()
                .type("can_i_leave")
                .content(payload)
                .from(_info).build();

            /*
            send leave_msg to all the nodes from which
            we haven't received an ack,
            (we are setting timeout to be 1 second,
            if the acks haven't arrived till then we resend the
            leave_msg)
             */
            while(!ready_to_leave()) {
                /*
                send message to all neighbours except
                the ones in _leave_acks
                 */
                for(String n : _connector._node_lookup.keySet()) {
                    if (!_leave_acks.contains(n)) {
                       _connector.send_message(leave_msg, new NodeInfo(n));
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }

            _am_i_leaving = false;
            _leave_acks.clear();

            /* now that we have all acks, and
            we are ready to leave,
            randomly select a neighbour and send it
            the NodeInfo strings for rest of the
            neighbours
             */


            String all_neighbours[] = _connector._node_lookup
                    .keySet()
                    .toArray(new String[0]);

            int rand = (int) Math.random()*all_neighbours.length;
            String chosen_node = all_neighbours[rand];

            HashMap<String, String> content = new HashMap<>();

            String data = "";
            for (String s : all_neighbours) {
                data += s + ",";
            }

            content.put("neighbours", data);

            Message neighbours_data = new Message.MessageBuilder()
                .type("neighbours_data")
                .content(content)
                .from(_info).build();

            _connector.send_message(neighbours_data, new NodeInfo(chosen_node));

            say_bye();
            write_hoplog();
            write_timelog();

        }

        else if (cmd.startsWith("fetch")){
            String fetch_request[]=cmd.split(" ");
            if (fetch_request.length != 3) {
                System.out.println("wrong usage of fetch!");
                return;
            }
            String ip_port[] = fetch_request[2].split(":");

            int port = Integer.parseInt(ip_port[1]) + 3000;
            Downloader dw = new Downloader(fetch_request[1], ip_port[0], port);
            dw.start();
        }
    }

    public synchronized boolean ready_to_leave() {
        ConcurrentHashMap<String,Integer> neighbours = new ConcurrentHashMap<>(_connector._node_lookup);
        Set<String> pending = neighbours.keySet();

        System.out.println(_leave_acks);
        for(String n : pending) {
            if(_leave_acks.contains(n)) {
               pending.remove(n);
            }
        }
        return pending.isEmpty();
    }

    public void say_bye() {
        Message bye_msg = new Message.MessageBuilder()
                .from(_info)
                .type("bye")
                .build();
        _connector.send_neighbours(bye_msg);
    }

    @Override
    public void run() {
        reset_logs();
        FileServer fs = new FileServer(this);
        fs.start();
        take_commands();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {

            }
        });
    }

    public static void main(String args[]) {
        Node node = new Node(new NodeInfo(args[0]));
        node.start();
        if (args.length == 2) {
            node.set_id(Integer.parseInt(args[1]));
        }

    }
}
