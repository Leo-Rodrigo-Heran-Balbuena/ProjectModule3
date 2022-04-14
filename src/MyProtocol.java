import client.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.io.Console;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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

    private final int ID = 1;


    public byte[] createHeader(int senderID, int receiverID, int numberOfMessage,
                               int messageID, int moreFragments, int seq, int ack, int bitSkip, int fragmentFlag) {

        byte[] header = new byte[8];

        header[0] = (byte) ((senderID * 16) + receiverID); // first byte, first 4 digits senderID second 4 is receiverID
        header[1] = (byte) (numberOfMessage); // used when reassembling packets, i.e. the order of the packets
        header[2] = (byte) (messageID); // will probably be used for all packets somehow
        header[3] = (byte) moreFragments; // will be set to 1 if there are more fragments coming, 0 if not
        header[4] = (byte) (seq); // sequence number of packet, may be used for reliable transmission and such
        header[5] = (byte) (ack); // ack number of packet, may be used for reliable transmission and such
        header[6] = (byte) (bitSkip); //bit that needs to be skipped, used for the last message lower than 24 bytes
        header[7] = (byte) (fragmentFlag); //will be set to 1 if the packet was a part of a fragmentation
        return header;

    }

    public byte[] mergeArrays(byte[] array1, byte[] array2) {
        byte[] result = new byte[array1.length + array2.length];
        for (int i = 0; i < (array1.length + array2.length); i++) { // might need reduce iterations by 2
            if (i < array1.length) {
                result[i] = array1[i];
            } else {                            //
                result[i] = array2[i];
            }
        }
        return result;
    }


    public MyProtocol(String server_ip, int server_port, int frequency) {
        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue); // Give the client the Queues to use

        new receiveThread(receivedQueue).start(); // Start thread to handle received messages!

        // handle sending from stdin from this thread.
        try {

            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

            String input = "";
            while ((input = console.readLine()) != null) {

                byte[] inputBytes = input.getBytes(); // get bytes from input
//                ByteBuffer toSend = ByteBuffer.allocate(inputBytes.length); // make a new byte buffer with the length of the input string
//                toSend.put(inputBytes, 0, inputBytes.length); // copy the input string into the byte buffer.
                Message msg;
                if ((inputBytes.length) > 2) {

                    ByteBuffer toSend = ByteBuffer.allocate(32); // match the form of DATA

                    if (inputBytes.length < 24) {
                        //padding
                        int necessaryPadding = 24 - inputBytes.length;
                        byte[] zeros = new byte[necessaryPadding];
                        byte[] result = mergeArrays(zeros, inputBytes);
                        byte[] header = createHeader(0, 0, 0,
                                0, 0, 0, 0, 0, 0);

                        toSend.put(mergeArrays(header, result),0, 32);
                        msg = new Message(MessageType.DATA, toSend);
                        sendingQueue.put(msg);
                    }

                    if (inputBytes.length == 24) {   // send directly once
                        byte[] header = createHeader(0, 0, 0, 0, 0, 0, 0, 0, 0);
                        toSend.put(mergeArrays(header, inputBytes), 0, 32);
                        msg = new Message(MessageType.DATA, toSend); //Create message
                        sendingQueue.put(msg); //send with header

                    }

                    if (inputBytes.length > 24) {
                        int totalPackets = inputBytes.length / 24; // total packet we need to send
                        int remainBytes = inputBytes.length % 24; // the Bytes that needs to be sent in the last packet
                        byte[] temp;
                        for (int i = 0; i < totalPackets; i++) {

                            if (totalPackets - i == 1 && remainBytes != 0) {
                                temp = Arrays.copyOfRange(inputBytes, i * 24, inputBytes.length);
                                // set more fragments flag to zero && set flag indicating fragment to 1
                            } else {
                                temp = Arrays.copyOfRange(inputBytes, i * 24, (i + 1) * 24);
                                // set more fragments flag to 1 and indicate position for re_fragmentation
                            }
                            byte[] header = createHeader(0, 0, 0, 0, 0, 0, 0, 0, 0);
                            toSend.put(mergeArrays(header, inputBytes), 0, 32);
                            msg = new Message(MessageType.DATA, toSend);
                            sendingQueue.put(msg);
                        }
                    }
                    //msg = new Message(MessageType.DATA, toSend);
                } else {
                    ByteBuffer toSend = ByteBuffer.allocate(2); // match the form of DATA-SHORT
                    //TODO: Check ack
                    msg = new Message(MessageType.DATA_SHORT, toSend);
                }
                //sendingQueue.put(msg);
            }
            System.out.println("While is not read");
        } catch (InterruptedException e) {
            System.exit(2);
        } catch (IOException e) {
            e.printStackTrace();
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

        // Handle messages from the server / audio framework
        public void run() {
            while (true) {
                try {
                    Message m = receivedQueue.take();

                    // look at header

                    if (m.getType() == MessageType.BUSY) { // The channel is busy (A node is sending within our detection range)
                        System.out.println("BUSY");
                        // if channel is busy then we do not try to send at the time
                    } else if (m.getType() == MessageType.FREE) { // The channel is no longer busy (no nodes are sending within our detection range)
                        System.out.println("FREE");
                        // if there is stuff to send then we can send now
                    } else if (m.getType() == MessageType.DATA) { // We received a data frame!
                        System.out.print("DATA: ");
                        printByteBuffer(m.getData(), m.getData().capacity()); //Just print the data
                        // look at the header and if fragmented, rebuild packet and print, if not print data

                    } else if (m.getType() == MessageType.DATA_SHORT) { // We received a short data frame!
                        System.out.print("DATA_SHORT: ");
                        printByteBuffer(m.getData(), m.getData().capacity()); //Just print the data
                        // incoming data for data short will mostly be acks
                    } else if (m.getType() == MessageType.DONE_SENDING) { // This node is done sending
                        System.out.println("x");
                    } else if (m.getType() == MessageType.HELLO) { // Server / audio framework hello message. You don't have to handle this
                        System.out.println("HELLO");
                    } else if (m.getType() == MessageType.SENDING) { // This node is sending
                        System.out.println("SENDING");
                    } else if (m.getType() == MessageType.END) { // Server / audio framework disconnect message. You don't have to handle this
                        System.out.println("END");
                        System.exit(0);
                    }
                } catch (InterruptedException e) {
                    System.err.println("Failed to take from queue: " + e);
                }
            }
        }
    }
}
