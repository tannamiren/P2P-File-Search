import java.util.concurrent.ConcurrentHashMap;

public class SearchKeeper extends Thread {
    private Node _node_ref;
    public final int _default_ttl = 18000; // Milliseconds
    /*
    _search_ids contains k:v pairs like:
    id : TTL
    when TTL hits 0 remove it.

    _search_peers contains k:v pairs like:
    id : NodeInfo of immediate sender of the request
     */
    public ConcurrentHashMap<String,Integer> _search_ids;
    public ConcurrentHashMap<String,NodeInfo> _search_peers;
    public SearchKeeper(Node node) {
        _node_ref = node;
        _search_ids = new ConcurrentHashMap<>();
        _search_peers = new ConcurrentHashMap<>();
    }

    public synchronized void add(String id) {
        _search_ids.put(id,_default_ttl);
    }

    public synchronized void add(String id, NodeInfo peer) {
        _search_ids.put(id,_default_ttl);
        _search_peers.put(id,peer);
    }

    public synchronized void remove(String id) {
        _search_ids.remove(id);
        if (_search_peers.containsKey(id)) {
            _search_peers.remove(id);
        }
    }

    public synchronized boolean has(String id) {
        return _search_ids.containsKey(id);
    }

    public synchronized boolean has_peer_for(String id) {
        return _search_peers.containsKey(id);
    }

    public synchronized NodeInfo get_peer(String id) {
        return _search_peers.get(id);
    }

    @Override
    public void run() {
        while(true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
            for(String id : _search_ids.keySet()) {
                if (_search_ids.get(id) == 0) {
                    this.remove(id);
                }
                else {
                    int new_ttl = _search_ids.get(id) - 1000;
                    _search_ids.put(id,new_ttl);
                }
            }
        }
    }
}
