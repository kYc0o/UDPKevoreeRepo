//import java.io.*;
import java.net.*;

public class UDPServer {
	
	public static void main(String args[]) throws Exception
	{
		System.setProperty("java.net.preferIPv6Stack" , "true");
		DatagramSocket serverSocket = new DatagramSocket(1234);
		byte[] receiveData = new byte[64];
		byte[] sendData = new byte[64];

		while(true)
		{
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
			serverSocket.receive(receivePacket);
			String sentence = new String( receivePacket.getData());
			System.out.println("RECEIVED: " + sentence);
			System.out.println("FROM:" + receivePacket.getAddress() + ":" + receivePacket.getPort());
			InetAddress IPAddress = receivePacket.getAddress();
			int port = receivePacket.getPort();
			String capitalizedSentence = sentence.toUpperCase();
			sendData = capitalizedSentence.getBytes();
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
			serverSocket.send(sendPacket);
			System.out.println("Sending back: " + capitalizedSentence);
			
		}
	}
}
