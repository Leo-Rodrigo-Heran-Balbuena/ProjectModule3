package client;

import javax.sound.midi.SysexMessage;
import java.nio.channels.SocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.sql.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;


public class Client {
    
    private SocketChannel sock;

    private boolean clogged;                                    // Checks if busy
    private int timer = 0;

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    private Message[] sentMessages = new Message[5];
    private int packetsSent = 0;

    private List<Message> fragments = new ArrayList<>();
    private List<Message> fragments2 = new ArrayList<>();

    private int ID;

    public void printByteBuffer(ByteBuffer bytes, int bytesLength){
        System.out.print("DATA: ");
        for(int i=0; i<bytesLength; i++){
            System.out.print( Byte.toString( bytes.get(i) )+" " );
        }
        System.out.println();
    }

    public Client(String server_ip, int server_port, int frequency,
                  BlockingQueue<Message> receivedQueue, BlockingQueue<Message> sendingQueue, int ID){
        this.receivedQueue = receivedQueue;
        this.sendingQueue = sendingQueue;
        this.ID = ID;
        SocketChannel sock;
        Sender sender;
        Listener listener;

        ByteBuffer toSend = ByteBuffer.allocate(32);
        for (int x = 0; x < sentMessages.length; x++) {
            sentMessages[x] = new Message(MessageType.DATA,toSend);
        }

        try{
            sock = SocketChannel.open();
            sock.connect(new InetSocketAddress(server_ip, server_port));
            listener = new Listener( sock, receivedQueue );
            sender = new Sender( sock, sendingQueue );

            sender.sendConnect(frequency);

            listener.start();
            sender.start();
        } catch (IOException e){
            System.err.println("Failed to connect: "+e);
            System.exit(1);
        }     
    }

    private class Sender extends Thread {
        private BlockingQueue<Message> sendingQueue;
        private SocketChannel sock;
        
        public Sender(SocketChannel sock, BlockingQueue<Message> sendingQueue){
            super();
            this.sendingQueue = sendingQueue;
            this.sock = sock;      
        }

        private void senderLoop(){
            boolean fragmentWait = false;
            while(sock.isConnected()){
                Random rand = new Random();
                try{
                    Message msg = sendingQueue.take();
                    Message state;
                    if (receivedQueue.isEmpty() && !fragmentWait) {
                        timer = rand.nextInt(2000);
                        TimeUnit.MILLISECONDS.sleep(timer);
                        if (timer < 750) {
                            attemptToSendData(msg);
                        } else {
                            TimeUnit.MILLISECONDS.sleep(timer);
                            sendingQueue.put(msg);
                        }
                    } else {
                        if ((state = receivedQueue.take()).getType().equals(MessageType.DATA)) {
                            if (state.getData().get(3) == 1 && state.getData().get(7) == 1) {
                                fragmentWait = true;
                                sendingQueue.put(msg);
                            } else if (state.getData().get(3) == 0 && state.getData().get(7) == 1) {
                                fragmentWait = false;
                                timer = rand.nextInt(2000);
                                TimeUnit.MILLISECONDS.sleep(timer);
                                if (timer < 1500) {
                                    attemptToSendData(msg);
                                } else {
                                    TimeUnit.MILLISECONDS.sleep(timer);
                                    sendingQueue.put(msg);
                                }
                            }
                        } else if (state.getType().equals(MessageType.BUSY)) {

                            timer = rand.nextInt(2000);
                            TimeUnit.MILLISECONDS.sleep(timer);
                            sendingQueue.put(msg);

                        } else if (state.getType().equals(MessageType.FREE)) {
                            timer = rand.nextInt(2000);
                            TimeUnit.MILLISECONDS.sleep(timer);
                            if (timer < 1500) {
                                attemptToSendData(msg);
                            } else {
                                TimeUnit.MILLISECONDS.sleep(timer);
                                sendingQueue.put(msg);
                            }
                        }
                    }

                } catch(InterruptedException e){
                    System.err.println("Failed to take from sendingQueue: "+e);
                }
            }
        }

        public void sendConnect(int frequency){
            ByteBuffer buff = ByteBuffer.allocate(4);
            buff.put((byte) 9);
            buff.put((byte) ((frequency >> 16)&0xff));
            buff.put((byte) ((frequency >> 8)&0xff));
            buff.put((byte) (frequency&0xff));
            buff.position(0);
            try {
                sock.write(buff);
            } catch(IOException e) {
                System.err.println("Failed to send HELLO" );
            }            
        }

        public void run(){
            senderLoop();
        }

        private void attemptToSendData(Message msg) {
            try {
                Random rand = new Random();
                if (msg.getType() == MessageType.DATA || msg.getType() == MessageType.DATA_SHORT ) {

                    for (int x = 0; x < sentMessages.length; x++) {
                        if (sentMessages[x].getData().get(2) == msg.getData().get(2)) {
                            System.out.println("[CONSOLE] - MESSAGE ALREADY SENT");
                            return;

                        }

                    }

                    ByteBuffer data = msg.getData();
                    data.position(0); //reset position just to be sure
                    int length = data.capacity(); //assume capacity is also what we want to send here!
                    ByteBuffer toSend = ByteBuffer.allocate(length+2);
                    if( msg.getType() == MessageType.DATA ){
                        toSend.put((byte) 3);
                    } else { // must be DATA_SHORT due to check above
                        toSend.put((byte) 6);
                    }
                    toSend.put((byte) length);
                    toSend.put(data);
                    toSend.position(0);

                    sentMessages[packetsSent % 5] = msg;
                    packetsSent++;



                    // TimeUnit.MILLISECONDS.sleep(rand.nextInt(1000));

                    sock.write(toSend);
                    // TimeUnit.MILLISECONDS.sleep(500);

                } else {
                    System.out.println("[CONSOLE] - Unable to send");
                }
            } catch(IOException e) {
                System.err.println("Alles is stuk!");
            }

        }

    }

    private class Listener extends Thread {
        private BlockingQueue<Message> receivedQueue;
        private SocketChannel sock;

        public Listener(SocketChannel sock, BlockingQueue<Message> receivedQueue){
            super();
            this.receivedQueue = receivedQueue;
            this.sock = sock;      
        }

        private ByteBuffer messageBuffer = ByteBuffer.allocate(1024);
        private int messageLength = -1;
        private boolean messageReceiving = false;
        private boolean shortData = false;

        private String parseMessage( ByteBuffer received, int bytesReceived ){
            // printByteBuffer(received, bytesReceived);
            try {
                for( int offset=0; offset < bytesReceived; offset++ ){
                    byte d = received.get(offset);
        
                    if( messageReceiving ){
                        if ( messageLength == -1) {
                            messageLength = (int) d;
                            messageBuffer = ByteBuffer.allocate(messageLength);
                        } else {
                            messageBuffer.put( d );
                        }
                        if( messageBuffer.position() == messageLength ){

                            printByteBuffer(messageBuffer, messageLength);
                            messageBuffer.position(0);

                            ByteBuffer temp = ByteBuffer.allocate(messageLength);
                            temp.put(messageBuffer);
                            temp.rewind();

                            if( shortData ){
                                receivedQueue.put( new Message(MessageType.DATA_SHORT, temp) );
                            } else {
                                receivedQueue.put( new Message(MessageType.DATA, temp) );
                            }                            
                            messageReceiving = false;
                        }
                    } else {
                        if ( d == 0x09 ){ // Connection successfull!
                            receivedQueue.put( new Message(MessageType.HELLO) );
                        } else if ( d == 0x01 ){ // FREE
                            receivedQueue.put( new Message(MessageType.FREE) );
                        } else if ( d == 0x02 ){ // BUSY
                            receivedQueue.put( new Message(MessageType.BUSY) );
                        } else if ( d == 0x03 ){ // DATA!
                            messageLength = -1;
                            messageReceiving = true;
                            shortData = false;
                        } else if ( d == 0x04 ){ // SENDING
                            receivedQueue.put( new Message(MessageType.SENDING) );
                        } else if ( d == 0x05 ){ // DONE_SENDING
                            receivedQueue.put( new Message(MessageType.DONE_SENDING) );
                        }else if ( d == 0x06 ){ // DATA_SHORT
                            messageLength = -1;
                            messageReceiving = true;
                            shortData = true;
                        }else if ( d == 0x08 ){ // END, connection closing
                            receivedQueue.put( new Message(MessageType.END) );
                        }
                    } 
                }
                    
            } catch (InterruptedException e){
                System.err.println("Failed to put data in receivedQueue: "+e.toString());
            }
            return "[CONSOLE] - Failed to receive data";
        }

        public void printByteBuffer(ByteBuffer bytes, int bytesLength){
            /*System.out.print("DATA: ");
            for(int i=0; i<bytesLength; i++){
                System.out.print( Byte.toString( bytes.get(i) )+" " );
            }
            System.out.println();*/
        }

        public void receivingLoop(){
            Random rand = new Random();
            int bytesRead = 0;
            ByteBuffer recv = ByteBuffer.allocate(1024);
            try{
                while( sock.isConnected() ){
                    bytesRead = sock.read(recv);
                    if ( bytesRead > 0 ) {

                        byte[] byteArray = Integer.toString(bytesRead).getBytes();
                        int senderID = ((int) byteArray[0]);
                        if (senderID == ID) {
                            break;
                        }

                        TimeUnit.MILLISECONDS.sleep(rand.nextInt(1000)+ 500);
                        parseMessage(recv,bytesRead);
                        TimeUnit.SECONDS.sleep(1);
                    } else {
                        break;
                    }
                    recv.clear();              
                }
            } catch(IOException e){
                System.err.println("Error on socket: "+e );
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }

        public void run(){
            receivingLoop();
        }

    }
}