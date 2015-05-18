//import java.io.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class UDPServer {

    private final int port;
    private final String repositoryRoot;

    private final Map<InetAddress, Session> sessions = new HashMap<InetAddress, Session>();

    public UDPServer(int port, String repositoryRoot) {
        byte[] receiveData = new byte[64];
        byte[] sendData = new byte[64];
        ByteBuffer tmpBB;

        this.port = port;
        this.repositoryRoot = repositoryRoot;

        System.setProperty("java.net.preferIPv6Stack", "true");
        try {
            DatagramSocket serverSocket = new DatagramSocket(port);
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            while (true) {
                // read a packet
                serverSocket.receive(receivePacket);

                byte[] data = receivePacket.getData();
                byte[] moreData = new byte[receivePacket.getLength()];
                System.arraycopy(data, receivePacket.getOffset(), moreData, 0, receivePacket.getLength());
                ByteBuffer buffer = ByteBuffer.wrap(moreData);

                // is the packet valid?
                int receivedCRC = (buffer.get(0) & 0x000000FF) | ((buffer.get(1) & 0x000000FF) << 8);
                buffer.putShort(0, (short) 0);
                int calculatedCRC = CRC16.crc16(moreData);

                if (receivedCRC != calculatedCRC) {
                    System.out.printf("\t Received CRC = %d, Calculated = %d, Message Length = %d.\n", receivedCRC, calculatedCRC, buffer.capacity());
                    calculatedCRC = CRC16.crc16(moreData);
                    continue;
                }

                // extract metadata from the packet
                InetAddress remoteAddress = receivePacket.getAddress();
                int remotePort = receivePacket.getPort();

                // detect packet type
                int cmd = buffer.get(ProtocolCommand.POS_CMD);

                // process command
                switch (cmd) {
                    case ProtocolCommand.CMD_GET_ARTIFACT:

                        buffer.position(ProtocolCommand.POS_DATA);
                        tmpBB = buffer.slice();
                        buffer.position(0);
                        byte[] tmp = new byte[tmpBB.capacity()];
                        tmpBB.get(tmp, 0, tmpBB.capacity());
                        String artifact = new String(tmp);

                        System.out.println("The artifact is " + artifact);

                        if (!sessions.containsKey(remoteAddress)) {
                            // Ok, a new request
                            Session session = new Session(artifact, remoteAddress, remotePort, repositoryRoot);
                            sessions.put(remoteAddress, session);
                            session.nextStep(serverSocket);
                        } else {
                            // well, maybe a packet was lost the first time, repeat the step
                            Session session = sessions.get(remoteAddress);
                            session.nextStep(serverSocket);
                        }
                        break;
                    case ProtocolCommand.CMD_GET_PACKET:
                        buffer.position(ProtocolCommand.POS_DATA);
                        tmpBB = buffer.slice();
                        if (sessions.containsKey(remoteAddress)) {

                            // well, maybe a packet was lost the first time, repeat the step
                            Session session = sessions.get(remoteAddress);
                            session.moveIfNecessaryToNextState();
                            session.setTemporaryBufferWithData(tmpBB);
                            session.nextStep(serverSocket);
                        }

                        break;
                }

            }
        } catch (SocketException e) {
            System.err.println("Failure using the server socket");
        } catch (IOException e) {
            System.err.println("Failure using the server socket");
        }


    }



	public static void main(String args[]) throws Exception
	{
        new UDPServer(1234, "/home/inti/programs/MIDDLEWARE2015/contiki/examples/kev-runtime/kev-components");

	}



}

class ProtocolCommand {

    // PACKAGE SIZE
    public static final int PACKAGE_SIZE = 58;

    // positions
    public static final int POS_CMD = 2;
    public static final int POS_DATA = 4;

    // requests to the server
    public static final byte CMD_GET_ARTIFACT = 3;
    public static final byte CMD_GET_PACKET = 4;

    // responses to the client
    public static final byte RESPONSE_SUMMARY = 5;
    public static final byte RESPONSE_PACKET = 6;
}



class Session {;
    int remotePort;
    InetAddress remoteAddress;
    String artifact;


    Map<String, String> deployUnits = new HashMap<String, String>();

    State state;
    int associatedValue;

    private ByteBuffer temporaryBufferWithData;

    public void setTemporaryBufferWithData(ByteBuffer temporaryBufferWithData) {
        this.temporaryBufferWithData = temporaryBufferWithData;
    }

    public ByteBuffer getTemporaryBufferWithData() {
        return temporaryBufferWithData;
    }

    public void moveIfNecessaryToNextState() {
        if (state == State.SendingSummary) state = State.SendingPacket;
    }

    private enum State {
        SendingSummary,
        SendingPacket,
    }

    Session(String artifact, InetAddress remoteAddress, int remotePort, String repositoryPath) {
        this.remotePort = remotePort;
        this.remoteAddress = remoteAddress;
        this.artifact = artifact;

        // create cache with all deploy units
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(String.format("%s/repository.repo", repositoryPath))));
            String line = br.readLine();
            while (line != null) {

                String [] ss = line.split(" ");

                if (!deployUnits.containsKey(ss[0])) {
                    deployUnits.put(ss[0], String.format("%s/%s", repositoryPath, ss[1]));
                }
                line = br.readLine();
            }

            br.close();
        } catch (FileNotFoundException e) { } catch (IOException e) {
            e.printStackTrace();
        }

        state = State.SendingSummary;
    }

    public void nextStep(DatagramSocket datagramSocket) throws IOException {
        ByteBuffer buffer;
        byte[] b = new byte[ProtocolCommand.PACKAGE_SIZE + 6];
        RandomAccessFile raf;
        File file;
        int crc;
        DatagramPacket sendPacket;

        file = null;
        if (!deployUnits.containsKey(artifact)) return;

        file = new File(deployUnits.get(artifact));

        if (!file.exists()) {
            System.out.printf("The file %s doesn't exist\n", file.getAbsolutePath());
            return;
        }

        switch (state) {
            case SendingSummary:
                long l = file.length();

                System.out.printf("FILE SIZE IS %d\n", l);

                long packets = l / ProtocolCommand.PACKAGE_SIZE;
                if (l % ProtocolCommand.PACKAGE_SIZE != 0) {
                    packets++;
                }

                byte[] data = new byte[8];
                buffer = ByteBuffer.wrap(data);
                // crc is 0 in the beginning
                buffer.putShort(0, (short)0);
                // command id
                buffer.put(ProtocolCommand.POS_CMD, ProtocolCommand.RESPONSE_SUMMARY);
                // number of data packets
                buffer.put(ProtocolCommand.POS_DATA, (byte)(packets & 0x000000FF) );
                buffer.put(ProtocolCommand.POS_DATA + 1, (byte)((packets & 0x0000FF00) >> 8) );
                // 64 bytes is the size of each packet (this is just a hint for the client, it now knows the size of the required buffer)
                buffer.put(ProtocolCommand.POS_DATA + 2, (byte)(64 & 0x000000FF) );
                buffer.put(ProtocolCommand.POS_DATA + 3, (byte) ((64 & 0x0000FF00) >> 8));
                // real CRC16
                crc = CRC16.crc16(data);
                buffer.put(0, (byte)(crc & 0x000000FF) );
                buffer.put(1, (byte)((crc & 0x0000FF00) >> 8) );
                // send
                sendPacket = new DatagramPacket(data, data.length, remoteAddress, remotePort);
                datagramSocket.send(sendPacket);
                System.out.println("\t Sending the sdfsdfsdf response back with crc " + crc);

                break;
            case SendingPacket:
                int packet_id = (temporaryBufferWithData.get(0) & 0x000000FF) | ((temporaryBufferWithData.get(1) & 0x000000FF) << 8);
                System.out.println("Asking for packet " + packet_id);
                raf = new RandomAccessFile(file, "r");
                raf.seek(packet_id * ProtocolCommand.PACKAGE_SIZE);
                int n = raf.read(b, ProtocolCommand.POS_DATA + 2, ProtocolCommand.PACKAGE_SIZE);
                byte[] tmp = new byte[ProtocolCommand.POS_DATA + 2 + n];
                System.arraycopy(b, 0, tmp, 0, tmp.length);
                buffer = ByteBuffer.wrap(tmp);
                // crc is 0 in the beginning
                buffer.putShort(0, (short)0);
                // command id
                buffer.put(ProtocolCommand.POS_CMD, ProtocolCommand.RESPONSE_PACKET);
                // packet id
                buffer.put(ProtocolCommand.POS_DATA, (byte)(packet_id & 0x000000FF) );
                buffer.put(ProtocolCommand.POS_DATA + 1, (byte)((packet_id & 0x0000FF00) >> 8) );
                // the real data is already there

                // real CRC16
                crc = CRC16.crc16(tmp);
                buffer.put(0, (byte)(crc & 0x000000FF) );
                buffer.put(1, (byte)((crc & 0x0000FF00) >> 8) );
                // send
                sendPacket = new DatagramPacket(tmp, tmp.length, remoteAddress, remotePort);
                datagramSocket.send(sendPacket);
                System.out.printf("\tSending chunk back with crc %d and size %d\n", crc, tmp.length);
                break;
        }

    }
}

class CRC16 {
    private static int to16Bits(int a) {
        return (a & 0x0000FFFF);
    }

    public static int crc16_add(byte b, int acc) {
        acc ^= to16Bits(b);
        acc  = (acc >> 8) | to16Bits(acc << 8);
        acc ^= to16Bits((acc & 0xff00) << 4);
        acc ^= (acc >> 8) >> 4;
        acc ^= (acc & 0xff00) >> 5;
        return acc;
    }

    public static int crc16(byte[] bytes) {
        int calculatedCRC = 0;
        for (byte b : bytes) {
            calculatedCRC = CRC16.crc16_add(b, calculatedCRC);
        }
        return calculatedCRC;
    }
}
