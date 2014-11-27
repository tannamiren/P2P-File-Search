import java.io.Serializable;
import java.util.HashMap;

public class Message implements Serializable {
    final NodeInfo to,from;
    final String type;
    final HashMap<String,String> content;
    private Message(MessageBuilder builder) {
        this.from = builder.from; this.to = builder.to;
        this.type = builder.type;
        this.content = builder.content;
    }

    @Override
    public String toString() {
        return String.format(from+" "+type+" "+content);
    }

    public NodeInfo getSender() {return from;}
    public NodeInfo getReceiver() {return to;}
    public String getType() {return type;}
    public HashMap getContent() {return content;}

    public static class MessageBuilder {
        private NodeInfo to,from;
        private String type;
        private HashMap<String,String> content = new HashMap<>();
        public MessageBuilder() {}
        public MessageBuilder from(NodeInfo from) {this.from = from; return this;}
        public MessageBuilder to(NodeInfo to) {this.to = to; return this;}
        public MessageBuilder type(String type) {this.type = type; return this;}
        public MessageBuilder content(HashMap<String,String> content) {this.content = content; return this;}
        public Message build() {return new Message(this);}
    }

    public static void main(String[] args) {
        Message msg = new Message.MessageBuilder()
                .type("application").build();
    }
}
