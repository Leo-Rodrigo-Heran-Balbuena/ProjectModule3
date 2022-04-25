package client;

import java.nio.channels.SocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
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
            while(sock.isConnected()){
                Random rand = new Random();
                try{
                    Message msg = sendingQueue.take();
                    System.out.println("Something found");
                    Message state;
                    if (!receivedQueue.isEmpty() && (state = receivedQueue.take()).getType() != MessageType.FREE) {
                        // Message state = receivedQueue.take();
                        if (state.getType() == MessageType.BUSY) {
                            System.out.println("Busy. Await for new slot");
                            clogged = true;
                            timer = rand.nextInt(200);
                            while (clogged) {
                                timer = timer -1;
                                if (timer == 0) {
                                    clogged = false;
                                    attemptToSendData(msg);
                                    break;
                                }
                            }
                        } else {
                            int toSendChance = rand.nextInt(10);
                            if (toSendChance < 8) {
                                attemptToSendData(msg);
                            }
                        }
                    } else {
                        int toSendChance = rand.nextInt(10);
                        if (toSendChance < 8) {
                            attemptToSendData(msg);
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
            try{
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
                if (msg.getType() == MessageType.DATA || msg.getType() == MessageType.DATA_SHORT ) {

                    System.out.println("[CONSOLE] - MESSAGE ID: " + msg.getData().getInt(2) + " MESSAGE FID: " + msg.getData().getInt(4) + " MESSAGE SENDER: " + msg.getData().getInt(0));

                    for (int x = 0; x < sentMessages.length; x++) {
                        if (sentMessages[x].getData().getInt(4) == msg.getData().getInt(4)) {
                            System.out.println("[CONSOLE] - MESSAGE ALREADY SENT");
                            return;
                        }

                    }

                    System.out.println("[CONSOLE] - Beginning Sending Process");
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

                    System.out.println("[CONSOLE] - Sending "+ Integer.toString(length)+" bytes!");
                    System.out.println("[CONSOLE] - Sending '"+ msg.toString() +"' ;");
                    System.out.println("[CONSOLE] - MESSAGE SENT");
                    sock.write(toSend);

                } else {
                    System.out.println("[CONSOLE] - Unable to send");
                }
            } catch(IOException e) {
                System.err.println("Alles is stuk!" );
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
                            /*System.out.println("pos: "+Integer.toString(messageBuffer.position()) );*/
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
                            // System.out.println("CONNECTED");
                            receivedQueue.put( new Message(MessageType.HELLO) );
                        } else if ( d == 0x01 ){ // FREE
                            // System.out.println("FREE");
                            receivedQueue.put( new Message(MessageType.FREE) );
                        } else if ( d == 0x02 ){ // BUSY
                            /*System.out.println("BUSY");*/
                            receivedQueue.put( new Message(MessageType.BUSY) );
                        } else if ( d == 0x03 ){ // DATA!
                            messageLength = -1;
                            messageReceiving = true;
                            shortData = false;
                        } else if ( d == 0x04 ){ // SENDING
                            // System.out.println("SENDING");
                            receivedQueue.put( new Message(MessageType.SENDING) );
                        } else if ( d == 0x05 ){ // DONE_SENDING
                            // System.out.println("DONE_SENDING");
                            receivedQueue.put( new Message(MessageType.DONE_SENDING) );
                        }else if ( d == 0x06 ){ // DATA_SHORT
                            messageLength = -1;
                            messageReceiving = true;
                            shortData = true;
                        }else if ( d == 0x08 ){ // END, connection closing
                            // System.out.println("END");
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
                        } else {
                            if ( Integer.parseInt((Integer.toString(bytesRead))) > 32) {
                                System.out.println("[CONSOLE] - Received "+ 32  +" bytes!");
                            } else {
                                System.out.println("[CONSOLE] - Received "+ Integer.parseInt((Integer.toString(bytesRead))) +" bytes!");
                            }

                        }
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