import client.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * This is just some example code to show you how to interact
 * with the server using the provided 'Client' class and two queues.
 * Feel free to modify this code in any way you like!
 */

public class MyProtocol {

    // The host to connect to. Set this to localhost when using the audio interface tool.
    private static String SERVER_IP = "netsys.ewi.utwente.nl"; //"127.0.0.1";
    // The port to connect to. 8954 for the simulation server.
    private static int SERVER_PORT = 8954;
    // The frequency to use.
    private static int frequency = 2800; //TODO: Set this to your group frequency!

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    private Message[] previouslySentPacket = new Message[5];

    private List<Message> receivedMessages;
    private List<Message> receivedMessages2;

    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_GREEN = "\u001b[32m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_BLUE = "\u001b[34m";
    public static final String ANSI_MAGENTA = "\u001B[35m";


    public static final String ANSI_RESET = "\u001B[0m";


    private HashMap<Integer,Integer> neighborNode;

    private int numberOfPacketsSent = 0;

    private int ID = 0;


    public byte[] createHeader(int senderID, int forwarderID, int numberOfMessage,
                               int messageID, int moreFragments, int seq, int ack, int bitSkip, int fragmentFlag) {

        byte[] header = new byte[8];

        header[0] = (byte) (senderID); // first byte, first 4 digits senderID second 4 is receiverID
        header[1] = (byte) (numberOfMessage); // used when reassembling packets, i.e. the order of the packets
        header[2] = (byte) (messageID); // will probably be used for all packets somehow
        header[3] = (byte) (moreFragments); // will be set to 1 if there are more fragments coming, 0 if not
        header[4] = (byte) (forwarderID); // ID OF THE NODE WHILE FORWARDING
        header[5] = (byte) (ack); // ack number of packet, may be used for reliable transmission and such
        header[6] = (byte) (bitSkip); //bit that needs to be skipped, used for the last message lower than 24 bytes
        header[7] = (byte) (fragmentFlag); //will be set to 1 if the packet was a part of a fragmentation
        // add hop count
        return header;

    }


    // out of bounds

    public byte[] mergeArrays(byte[] array1, byte[] array2) {
        int counter1 = 0, counter2 = 0;
        byte[] result = new byte[array1.length + array2.length];
        for (int i = 0; i < (array1.length + array2.length); i++) { // might need reduce iterations by 2
            if (i < array1.length) {
                result[i] = array1[counter1];
                counter1++;
            } else {                            //
                result[i] = array2[counter2];
                counter2++;
            }
        }
        return result;
    }

    private void printByConsole(String msg) {
        System.out.println(ANSI_YELLOW + "[CONSOLE]: "+ msg + ANSI_RESET);
    }

    private void printByDrama(String msg) {
        try {
            for (int x = 0; x < msg.length(); x++) {
                System.out.print(msg.charAt(x));
                TimeUnit.MILLISECONDS.sleep(105);
            }
            System.out.println("");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void printByNode(int nodeID, String msg) {
        if (nodeID >= 0 && nodeID < 32) {
            System.out.print(ANSI_BLUE + "[" + nodeID + "]" + ": "+ ANSI_RESET);
        } else if (nodeID > 32 && nodeID < 64) {
            System.out.print(ANSI_RED + "[" + nodeID + "]" + ": "+ ANSI_RESET);
        } else if (nodeID > 64 && nodeID < 96) {
            System.out.print(ANSI_GREEN + "[" + nodeID + "]" + ": "+ ANSI_RESET);
        } else {
            System.out.print(ANSI_MAGENTA + "[" + nodeID + "]" + ": "+ ANSI_RESET);
        }
        printByDrama(msg);
    }

    public MyProtocol(String server_ip, int server_port, int frequency) {

        /* Initialization */

        Random rand = new Random();
        this.ID = rand.nextInt(128);

        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();
        receivedMessages = new ArrayList<>();
        receivedMessages2 = new ArrayList<>();
        neighborNode = new HashMap<>(4);


        new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue, ID);
        new receiveThread(receivedQueue).start();


        /* Main reading loop */

        try {

            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

            String input = "";
            while ((input = console.readLine()) != null) {
                byte[] inputBytes = input.getBytes();
                Message msg;
                if ((inputBytes.length) > 2) {
                    generateMessage(inputBytes);
                } else {
                    ByteBuffer toSend = ByteBuffer.allocate(2); // match the form of DATA-SHORT
                    //TODO: Check ack
                    msg = new Message(MessageType.DATA_SHORT, toSend);
                    sendingQueue.put(msg);

                }
                //sendingQueue.put(msg);
            }
        } catch (InterruptedException e) {
            System.exit(2);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // receiverID?

    private void generateMessage(byte[] inputBytes) {
        generateMessage(inputBytes, false, false, 0);
    }

    private void generateMessage(byte[] inputBytes, boolean fragment, boolean last, int messageNumber) {
        ByteBuffer toSend = ByteBuffer.allocate(32); // match the form of DATA

        try {
            if (inputBytes.length <= 24) {
                int necessaryPadding = 24 - inputBytes.length;
                int messageID = new Random().nextInt(128);
                printByConsole("ID CHOSEN FOR MESSAGE: " + messageID);
                /*System.out.println(ANSI_YELLOW + "[CONSOLE] - ID CHOSEN FOR MESSAGE: " + messageID + ANSI_RESET);*/

                byte[] zeros = new byte[necessaryPadding];
                byte[] result = mergeArrays(zeros, inputBytes);
                byte[] header = null;


                if (fragment) { //if DATA is fragment packet
                    if (last) { //if this message is the last fragment packet
                        header = createHeader(ID, 0, messageNumber, messageID, 0, 0, 0, necessaryPadding, 1); //Set the moreFragments flag to 0
                    } else {
                        header = createHeader(ID, 0, messageNumber, messageID, 1, 0, 0, necessaryPadding, 1); //Set the moreFragments flag to 1
                    }
                } else { //if DATA is not fragment packet
                    header = createHeader(ID, 0, messageNumber, messageID, 0, 0, 0, necessaryPadding, 0); //Set moreFragments and lastFragmentFlag to 0
                }

                toSend.put(mergeArrays(header, result), 0, 32);
                Message msg = new Message(MessageType.DATA, toSend);
                previouslySentPacket[numberOfPacketsSent % 5] = msg;
                numberOfPacketsSent++;

                sendingQueue.put(msg);

            } else {
                generateFragmentedMessage(inputBytes);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private void generateFragmentedMessage(byte[] inputBytes){

        Scanner sc = new Scanner(System.in);
        // double fragments = Math.ceil(inputBytes.length / 24);
        printByConsole("MESSAGE LENGTH: "+inputBytes.length);
        int fragments;
        if (inputBytes.length % 24 == 0) {
            fragments = inputBytes.length / 24;
        } else {
            fragments = (inputBytes.length / 24) + 1;
        }

        printByConsole("NUMBER OF FRAGMENTS: " +fragments);
        printByConsole("PRESS ENTER TO CONTINUE");


        /*System.out.println("[CONSOLE] - NUMBER OF FRAGMENTS: " +fragments);
        System.out.println("[CONSOLE] - PRESS ENTER TO CONTINUE");*/
        String a = sc.nextLine();

        for (int x = 0; x < fragments; x++) {

            byte[] toFragment;

            if (fragments - x == 1) {

                toFragment = Arrays.copyOfRange(inputBytes, x * 24, inputBytes.length);
                generateMessage(toFragment, true, true, x + 1);

            } else {
                toFragment = Arrays.copyOfRange(inputBytes, x * 24, (x+1) * 24);
                generateMessage(toFragment, true, false, x + 1);
            }

        }

    }

    public static void main(String args[]) {

        if (args.length > 0) {

            frequency = Integer.parseInt(args[0]);

        }

        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);

    }


    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        public void printByteBuffer(ByteBuffer bytes, int bytesLength) {
            for (int i = 0; i < bytesLength; i++) {
                System.out.print(Byte.toString(bytes.get(i)) + " ");
            }
            System.out.println();
        }

        public void run() {
            while (true) {

                try {

                    Message m = receivedQueue.take();
                    // look at header
                    if (m.getType() == MessageType.DATA) { // We received a data frame!
                        ByteBuffer temp = m.getData();
                        int padding = (int) temp.get(6); // create methods for parsing
                        int fragmented = temp.get(7);
                        int moreFragments = temp.get(3);

                        // neighborNode.put(ID, 0);
                        if (temp.get(4) == 0) {
                            neighborNode.put( (int) temp.get(0), 6);
                        } else {
                            neighborNode.put( (int) temp.get(4), 6);
                        }

                        neighborNode.replaceAll((i, v) -> v - 1);

                        for (Integer j : neighborNode.keySet()) {
                            if (neighborNode.get(j) == 0) {
                                neighborNode.remove(j);
                            }
                        }


                        printByConsole("REACHABLE NODES" + neighborNode.keySet());

                        if ((m.getData().get(0)) == ID) {

                            continue;

                        } else {
                            /*System.out.print("[CONSOLE] - DATA: ");*/

                            if (fragmented == 1) {
                                if (receivedMessages.size() > 0 && receivedMessages.get(0) != null &&
                                        temp.get(0) != receivedMessages.get(0).getData().get(0)) {

                                    receivedMessages2.add(m);
                                } else {

                                    receivedMessages.add(m);

                                }
                                if (receivedMessages.size() > 1) {
                                    boolean complete = false;
                                    int size = receivedMessages.size();
                                    for (Message receivedMessage : receivedMessages) {
                                        if (receivedMessage.getData().get(3) == 0 && receivedMessage.getData().get(7) == 1 &&
                                                size == receivedMessage.getData().get(1)) {

                                            complete = true;

                                        }
                                    }
                                    if (complete) {
                                        for (Message x : receivedMessages) {
                                            if (x.getData().get(3) == 0 && x.getData().get(7) == 1) {
                                                padding = x.getData().get(6);
                                            }
                                        }

                                        int space = ((size - 1) * 24) + (24 - padding);
                                        ByteBuffer data = ByteBuffer.allocate(space);

                                        for (int i = 1; i <= size; i++) {
                                            for (int x = 0; x < size; x++) {
                                                if (receivedMessages.get(x).getData().get(1) == i) {
                                                    if (size - i == 0) {
                                                        data.put(Arrays.copyOfRange(receivedMessages.get(x).getData().array(), 8 + padding, 32));
                                                    } else {
                                                        data.put(Arrays.copyOfRange(receivedMessages.get(x).getData().array(), 8, 32));
                                                    }
                                                }
                                            }
                                        }
                                        receivedMessages = new ArrayList<>();

                                        printByNode(m.getData().get(0), new String(data.array(), StandardCharsets.US_ASCII));
                                        /*System.out.println("Node " + m.getData().get(0) + ": " + new String(data.array(), StandardCharsets.US_ASCII));*/
                                    } else {
                                        printByConsole("A fragment packet has been lost along the way for RM1");
                                    }
                                }

                                if (receivedMessages2.size() > 1) {
                                    boolean complete = false;
                                    int size = receivedMessages2.size();
                                    for (Message receivedMessage : receivedMessages2) {
                                        if (receivedMessage.getData().get(3) == 0 && receivedMessage.getData().get(7) == 1 &&
                                                size == receivedMessage.getData().get(1)) {

                                            complete = true;

                                        }
                                    }
                                    if (complete) {

                                        for (Message x : receivedMessages2) {
                                            if (x.getData().get(3) == 0 && x.getData().get(7) == 1) {
                                                padding = x.getData().get(6);
                                            }
                                        }

                                        int space = ((size - 1) * 24) + (24 - padding);
                                        ByteBuffer data = ByteBuffer.allocate(space);

                                        for (int i = 1; i <= size; i++) {
                                            for (int x = 0; x < size; x++) {
                                                if (receivedMessages2.get(x).getData().get(1) == i) {
                                                    if (size - x == 1) {
                                                        data.put(Arrays.copyOfRange(receivedMessages2.get(x).getData().array(), 8 + padding, 32));
                                                    } else {
                                                        data.put(Arrays.copyOfRange(receivedMessages2.get(x).getData().array(), 8, 32));
                                                    }
                                                }
                                            }
                                        }
                                        receivedMessages2 = new ArrayList<>();
                                        /*printByNode(m.getData().get(0), new String(data.array(), StandardCharsets.US_ASCII));*/
                                        /*System.out.println("Node " + m.getData().get(0) + ": " + new String(data.array(), StandardCharsets.US_ASCII));*/
                                    } else {
                                        printByConsole("A FRAGMENT PACK HAS BEEN LOST ALONG THE WAY FOR RM2");
                                    }
                                }
                            } else {
                                byte[] data = null;
                                if (padding > 0) {
                                    data = new byte[24 - padding];
                                    for (int i = 0; i < data.length; i++) {
                                        data[i] = m.getData().get(8 + padding + i);
                                    }
                                }

                                String string = "";

                                if (m.getData().hasArray() && data != null) {
                                    string = new String(data, StandardCharsets.US_ASCII);
                                }
                                printByNode(m.getData().get(0), string);
                            }


                            boolean alreadySent = false;

                            for (Message message : previouslySentPacket) {
                                if (message != null) {
                                    if (m.getData().get(2) == message.getData().get(2)) {
                                        alreadySent = true;
                                    }
                                }
                            }

                            if (!alreadySent) {
                                // Changing the forwarder ID
                                ByteBuffer dataInfo = m.getData();
                                byte[] data = dataInfo.array();
                                data[4] = (byte) ID;
                                ByteBuffer dataInfo1 = ByteBuffer.allocate(32);
                                dataInfo1.put(data);
                                Message toSend = new Message(MessageType.DATA, dataInfo1);
                                sendingQueue.put(toSend);
                            }

                        }

                        // printByteBuffer(m.getData(), m.getData().capacity()); //Just print the data

                        // index 6
                        // look at the header and if fragmented, rebuild packet and print, if not print data

                    } else if (m.getType() == MessageType.DATA_SHORT) { // We received a short data frame!
                        printByConsole("DATA SHORT: ");
                        /*System.out.print("[CONSOLE] - DATA SHORT: ");*/
                        printByteBuffer(m.getData(), m.getData().capacity()); //Just print the data
                        // incoming data for data short will mostly be acks
                    } else if (m.getType() == MessageType.DONE_SENDING) { // This node is done sending
                        printByConsole("SENDING ACCOMPLISHED");
                    } else if (m.getType() == MessageType.HELLO) { // Server / audio framework hello message. You don't have to handle this

                        printByConsole("WELCOME TO GROUP 23! MESSAGING APP");
                        printByConsole("ID: " + ID);
                        printByConsole("ENTER MESSAGE: ");

                        /*System.out.println(ANSI_YELLOW+ "[CONSOLE] - ID: " + ID + ANSI_RESET);
                        System.out.println(ANSI_YELLOW+ "[CONSOLE] - Type" + ANSI_RESET);*/
                    } else if (m.getType() == MessageType.SENDING) { // This node is sending
                        printByConsole("SENDING");

                    } else if (m.getType() == MessageType.END) { // Server / audio framework disconnect message. You don't have to handle this
                        printByConsole("FIN");
                        System.exit(0);
                    }
                } catch (InterruptedException e) {
                    System.err.println("Failed to take from queue: " + e);
                }
            }
        }
    }
}
