% Peer to Peer File search 
% Swair Shah, Miren Tanna
% 29-July-2014

# System Design

## Node
The core behavior of a class is implemented in Node class.
Node is to be started by passing ip:port in command line,
followed by a node_id.

Node has the ability to establish TCP channels to other
nodes via `Connector`. This class implements setting up
of TCP channels to neighbours, and maintaining
Lists of Sockets and output streams to connected neighbours.

As soon as a node is alive at a specific ip:port, it will 
listen for incoming TCP connections continuosly. 

In order to connect to another node of a cluster, we have to
know the IP:PORT pair for the another node. `node` can call
`join_neighbour` method of `_connector`, to join a neighbour.
This method sends a `join` message to another node.
Joining a neighbour adds an entry to `_node_lookup` and
corresponding socket and outputstreams to other Lists in 
`_connector`. Any future messages to a neighbour are sent
via this established TCP connection. 

As soon as a node gets a `join` message 
it inturn joins the sender of the message. When 
`_connector` instance variable of a `node` accepts
a connection from another node, it spawns an instance of `ListenerThread`,
and from that point on all incoming `messages` with 
the other node comes to this ListenerThread.

## Message Types
A node can send/receive the following types of messages:

1. join 
2. fetch
3. search
4. search_result
5. bye
6. bye_ack
7. can_i_leave
8. yes_you_can
9. no_you_cannot

### Join
A `Join` message contains message type `join`, sender and receiver
`NodeInfo`. 

This message is sent when a node wishes to join a cluster of another 
node. Since the channels are reliable (TCP), there is no acknowledgment
of a message and sender assumes that message is going to be delivered.

The sender, N1 sends a `Join` message to N2. Then N1 adds N2 to its
`_neighbours` list, and establishes a TCP connection using its
`Connector`.

The message on the receiving end (N2) of this a `Join` message in turn
adds N1 to its `_neighbours` list and establishes a TCP connection to it.

### Leave
Leave protocol is initiated by a node by sending a `can_i_leave` message
to its neighbours and sets a flag `_am_i_leaving` to `true`,
and waits for `yes_you_can` replies for 1 second. 

Until it received the replies from all its __current__ neighbours,
it keeps sending `can_i_leave` messages to its neighbours.

When the node receives `yes_you_can` replies from a neighbour,
it adds the sender of that message to `_leave_acks` list. The node
no longer sends `can_i_leave` to the neighbours in `_leave_acks`

As soon as there are no extra neighbours in `_node_lookup` as
compared to `_leave_acks`, the node randomly chooses one of its
neighbours and sends the list of its neighbours to it.
and now the node uses `bye` protocol to depart from the cluster
gracefully.

When a node received `can_i_leave` message, if it has its
`_am_i_leaving` flag set to `true` then it checks the corresponding 
node ids. if it has a lower id (higher priority) it dosesn't send
`yes_you_can` reply (Instead sends `no_you_cannot`, which is 
implemented just for debugging purposes and plays no role
in the protocol execution). 

If it has a lower priority then it sends a `yes_you_can` reply
to the neighbours.

Implementation is as follows:

```java
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
```

```java
public synchronized boolean ready_to_leave() {
    ConcurrentHashMap<String,Integer> neighbours = 
        new ConcurrentHashMap<>(_connector._node_lookup);
    Set<String> pending = neighbours.keySet();

    System.out.println(_leave_acks);
    for(String n : pending) {
        if(_leave_acks.contains(n)) {
           pending.remove(n);
        }
    }
    return pending.isEmpty();
}
```

This protocol avoids deadlocks by breaking ties based on `node_id`. This also makes sure
that concurrent leave works fine. 

Say `n0` is connected to `n2` and `n3` and `n1`. `n1` is connected to `n4` and `n5` also.
If `n0` and `n1` start Leave protocol concurrently, both have set their
`_am_i_leaving` to true. 

When `n0` receives `can_i_leave` from `n1`, it won't send a `yes_you_can`,
and `n1` in turn will keep looping in `ready_to_leave()` method.

But `n1` meanwhile __will__ send `yes_you_can` to `n0` considering it 
has a higher `node_id` and a lower priority. When `n0` gets `yes_you_can`
from all its neighbours, it picks one of its neighbours and makes it join
all other neighbours of `n0`. So this potentially changes `_node_lookup` 
of `n1` and in next `while(!ready_to_leave())` execution `n1` will send
`can_i_leave` to its updated neighbourhood (except from the nodes it
already received acks). and when it finally gets permission from all
its neighbours (old and new), it can leave.

#### Bye protocol

`Bye protocol` deals with closing of listener threads.
It is the last thing done as a part of Leave protocol.
It is handled in listener threads, and not passed on to 
`Connector` or `Node` class.

```
 if (message type is bye)
    send(bye_ack message to sender)
    terminate the current listener thread
    remove (sender from _node_loookup)

 if (message type is bye_ack)
    terminate the current listener thread
    remove (sender from _node_loookup)
```

### Search
The implementation of search procedure in Node class has
two important parts, one is the `SearchAgent` and other is 
`SearchKeeper`.

#### SearchAgent

`SearchAgent` extends Thread and handles queries initiated
by the current node. For every query initiated by node,
there is a `SearchAgent` thread spawned. Each initiated
search has a UUID. Node stores UUID and reference to
corresponding `SearchAgent`. 
`SearchAgent` takes care of the timeout and increasing 
hop_count after specific timeouts.

First search attempt is sent with hop_count 1. Timeout happens at
t_out\*1. Next query is sent with double the hop_count,
and the timeout is also doubled. This goes on till
hop_count exceeds 16. So last query attempt is made with
a `search` message of hop_count 16. 

During all this if a search_result is received corresponding
to a query initited by current node, the SearchAgent 
corresponding to that UUID is terminated. So it won't send any
more queries with increased hop_count. The node can still
receive the results for that query.

SearchAgent makes sure to terminate itself when hop_count
reaches 16.

#### SearchKeeper
`SearchKeeper` extends Thread and handles queries which are
not initiated by current node. There is one SearchKeeper per
node. It handles all relayed searches. SearchKeeper maintains
search IDs (UUID) and the peer which sent this search result
to it (so that the node can send the same neighbour the result
of that search). It also maintains a TTL counter for each
query, and all queries are expired by the SearchKeeper in
18 seconds (this can be changed of course, but since our
node expects queries to be sent only till hop_count 16,
18 seconds seem to be the reasonable time to expire
a relayed search).

SearchKeeper has following instance variables to
store this information:
```java
/*
_search_ids contains k:v pairs like:
id : TTL
when TTL hits 0 remove it.

_search_peers contains k:v pairs like:
id : NodeInfo of immediate sender of the request
 */
public ConcurrentHashMap<String,Integer> _search_ids;
public ConcurrentHashMap<String,NodeInfo> _search_peers;
```

SearchKeeper thread runs the following while loop:

```java
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
```

A `Search` message contains 
* message type `search`, 
* `NodeInfo` of sender, receiver, initiator
* current hopcount for the query 
* UUID of the search (which the initiator sets)

#### Local FileSearch
`Local FileSearch` attempts to search its local directory 
using the keyword in search query. The keyword is checked 
against the filename as well as its corresponding keywords 
in the metadata file.

```
 if(localFileSearch returns results)
   send search_results back to requesting node
 else 
   do nothing
```

#### FileSearch on network nodes
If Local File Search fails, the search request is passed on 
to the neighbors of current node, except for the node where 
the sending node received the request from. Each time a 
request is forwarded to neighboring nodes, the hop count 
is monotonically reduced. If hopcount<=1, the search 
request is not forwarded ahead. 

Say `n0` is connected to `n1`, `n1` connected to `n2` and 
`n3`. `n3` is connected to `n4` and `n5` also. If the search 
request begins from `n0`, and its local search fails, it 
forwards the request to `n1`. If the request fails at `n1` 
too, the request is send to `n2` and `n3` but not to `n0`. 
If the request was forwarded from `n3`, it would be sent 
to `n4` and `n5`, but not to `n1`.


#### Search_Result
This message is sent when a search query returns a successful 
result. This message is sent after a successful local FileSearch. 
A node receiving this message, could possibly be the initiator 
of the search request or an intermediary node.

```
 if(SearchAgent contains UUID returned by search_result)
   search_result has reached initiator
   display results
   terminate the search agent
 else
   search_result has reached intermediate node
   lookup for peer of current node and forward the search_result to it
```

### Fetch
`fetch` is used to download a file from the desired node. The 
filename and ip:port address of the node is passed when the 
fetch request is made. 

The following components work while fetching the file. 

#### Downloader
`Downloader` extends Thread and handles fetch requests initiated by 
the current node. Downloader thread starts each time a request is made.
`filename`, `server ip` and `server port` is passed as parameters to 
Downloader. As the original server port is already in use, we offset the
original server port with a pre-decided value to handle the incoming fetch
requests. 

`Downloader` creates a socket connection with the `server ip` and `server port` 
passed to it as parameters. Metadata content and file contents are read 
using `InputStreamReader`. The read data is converted to bytes and written
to the corresponding files. A file is assumed to be of a large
size(>1 MB). In order to write the file efficiently, the file is 
written in chunks of specific size. File writing is done as following:

```java
 public void copyStream(InputStream input, OutputStream output)
         throws IOException {
     byte[] buffer = new byte[1024];
     int bytesRead;
     while ((bytesRead = input.read(buffer)) != -1) {
         output.write(buffer, 0, bytesRead);
     }
 }
```

#### FileServer, FileTransfer and Downloader
After a node is alive, `FileServer` runs at each node. It also extends Thread.
FileServer is responsible for spawning `FileTransfer` threads.

When a node receives a `fetch` command, it spawns a `Downloader` thread.
(We assume that the fileserver port for a server is its listener port+3000).
Downloader connects to fileserver and asks for a file, gets the metadata
for the file and then receives the byte stream for that file.

It opens a port for TCP communication at the port passed to it as a 
parameter. This is the same port with which the `Downloader` establishes connection.
It accepts connection from the client node, and passes it on to `FileTransfer`.

Data transfer is done only when the requested file exists at the current node. 
The file is sent using an `OutputStream` in chunks of pre-decided size. 

#How to Compile and Run
To simulate a network of M nodes, ensure all the JAVA files are available on
each nodes' local directory. We assume that some sample files are already available
at these nodes. A metafile with information of each of these files with their
corresponding keywords should also be present. No node is aware which files are 
present at other nodes. The format of metafile should be:
```
filename1 keyword1,keyword2,...,keywordN
filename2 keyword1,keyword2,...,keywordN
.
.
filenameN keyword1,keyword2,...,keywordN
```
At each node compile all the files in the directory using the command:
```
javac *.java
```
To run the program, you should know the IP address of the current node:
```
java Node IPAddress:Port
```
To find out what options are supported by the program, enter:
```
help
```
To join other nodes in the network:
```
join IPAddress:Port
```
To search for a file, enter:
```
search search_term
```
To fetch the file based on the list of results received, enter:
```
fetch file_name IPAddress:Port
```
To leave the network, enter:
```
leave
```
