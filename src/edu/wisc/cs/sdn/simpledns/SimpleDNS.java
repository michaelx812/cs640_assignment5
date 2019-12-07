package edu.wisc.cs.sdn.simpledns;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveAction;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdata;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataString;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;

class CSVEntry
{
	InetAddress ip;
	int mask;
	String location;
}
public class SimpleDNS 
{
	private static ArrayList<CSVEntry> csv_entries;
	private static InetAddress root_server_ip;
	private static final int RECEIVE_PORT_NUM = 8053;
	private static final int SERVER_PORT_NUM = 53;


	public static void main(String[] args)
	{
		System.out.println("Hello!!!");
        if(args.length!=4 || !args[0].equals("-r") || !args[2].equals("-e")){
			System.out.println("Error :invalid arguments");
			System.exit(1);
		}

		try{
			root_server_ip = InetAddress.getByName(args[1]);
		}catch(UnknownHostException e){
			System.out.println("Invalid server IP");
			System.exit(1);
		}

		parse_csv(args[3]);

		DatagramSocket socket = null;
		DatagramPacket packet = new DatagramPacket(new byte[1500], 1500);

		try{	
		socket = new DatagramSocket(RECEIVE_PORT_NUM);
		}catch(Exception e){
			e.printStackTrace();
			System.exit(1);
		}
		while(true){
			try{
				socket.receive(packet);
				System.out.println("Socket received!!!!!!!");
				
				DNS dns = DNS.deserialize(packet.getData(), packet.getLength());
				if(dns.getOpcode() != (byte)0){
					continue;
				}
				List<DNSQuestion> dns_qs = dns.getQuestions();
				DNSQuestion dnsquestion =  dns_qs.get(0);
				System.out.println(dnsquestion.toString());
				short q_type = dnsquestion.getType();
				if( q_type!= DNS.TYPE_A && q_type!= DNS.TYPE_AAAA && 
					q_type!=DNS.TYPE_CNAME && q_type!=DNS.TYPE_NS){
					continue;
				}
				DatagramPacket receive_pkt = null;
				if(dns.isRecursionDesired()){
					receive_pkt = handle_recur(packet,root_server_ip);
				}else{
					receive_pkt = handle_non_recur(packet,root_server_ip);
				}
				if(receive_pkt==null){
					System.out.println("Get receive_pkt==null; should never happen!!!!!");
					System.exit(1);
				}
				if(q_type == DNS.TYPE_A){
					receive_pkt = addCSVRecord(receive_pkt);
				}
				
				//DatagramPacket answer = new DatagramPacket(receive_pkt.getData(), receive_pkt.getLength(),packet.getAddress(),RECEIVE_PORT_NUM);
				receive_pkt.setPort(packet.getPort());
				receive_pkt.setAddress(packet.getAddress());

				//DNS curdns = DNS.deserialize(answer.getData(),answer.getLength());
				//System.out.println(curdns.toString());
				socket.send(receive_pkt);
				
			}catch(Exception e){
				e.printStackTrace();
			}
				
			


			
		}


	}

	private static DatagramPacket handle_non_recur(DatagramPacket packet, InetAddress server_ip) throws Exception{
		DatagramSocket socket = new DatagramSocket();
		DatagramPacket forward_pkt = new DatagramPacket(packet.getData(),packet.getLength(),server_ip,SERVER_PORT_NUM);
		DatagramPacket receive_pkt = new DatagramPacket(new byte[1500], 1500);
		System.out.println("Send query to"+server_ip.toString());
		socket.send(forward_pkt);
		socket.receive(receive_pkt);
		socket.close();
		return receive_pkt;
	}

	private static DatagramPacket handle_recur(DatagramPacket packet, InetAddress server_ip) throws Exception{
		DatagramPacket query_pkt = packet;
		
		List<DNSResourceRecord> recordList = new ArrayList<DNSResourceRecord>();
		while(true){
			DatagramPacket in_pkt = recur_helper(query_pkt, server_ip, 20);
			if(in_pkt != null){
				DNS dns = DNS.deserialize(in_pkt.getData(), in_pkt.getLength());
				List<DNSResourceRecord> answers = dns.getAnswers();
				for(DNSResourceRecord record : answers){
					if(record.getType() == DNS.TYPE_A || record.getType() == DNS.TYPE_AAAA){
						for(DNSResourceRecord pastRecord : recordList){
							dns.addAnswer(pastRecord);
						}
						byte[] buf = dns.serialize();
						DatagramPacket returnPacket = new DatagramPacket(buf,buf.length);
						return returnPacket;
					}else if(record.getType() == DNS.TYPE_CNAME){
						recordList.add(record);
					}
				}

			}else{
				return null;
			}
		}

	}

	private static DatagramPacket recur_helper(DatagramPacket packet,InetAddress server_ip,int ttl) throws Exception{
		if(ttl == 0){
			return null;
		}
		DatagramPacket in_pkt = handle_non_recur(packet, server_ip);
		DNS dns = DNS.deserialize(in_pkt.getData(), in_pkt.getLength());
		List<DNSResourceRecord> answers = dns.getAnswers();
		if(answers.size()>0){
			return in_pkt;
		}
		List<DNSResourceRecord> auths = dns.getAuthorities();
		List<DNSResourceRecord> additions = dns.getAdditional();
		for(DNSResourceRecord auth_entry: auths){
			for(DNSResourceRecord add_entry: additions){
				String auth_string = auth_entry.getData().toString();
				System.out.println("processing auth:"+auth_string+"     addtional:"+add_entry.getName());
				String add_string = add_entry.getData().toString();
				if(auth_entry.getType()==DNS.TYPE_NS && 
				//||add_entry.getType()==DNS.TYPE_AAAA
					(add_entry.getType()==DNS.TYPE_A ) && 
					auth_string.equals(add_entry.getName())){
						InetAddress nxt_server = InetAddress.getByName(add_string);
						System.out.println("nxt server:"+auth_string+" : "+nxt_server);
						DatagramPacket nxt_pkt= recur_helper(packet,nxt_server,ttl-1);
						if(nxt_pkt!=null){
							return nxt_pkt;
						}
				}
			}
		}
		System.out.println("default null of recur func");
		return null;
	}

	private static void parse_csv(String filename){
		csv_entries = new ArrayList<CSVEntry>();
		try{
			BufferedReader reader  = new BufferedReader(new FileReader(filename));
			String line = null;
			while((line=reader.readLine())!=null){
				String[] info = line.split(",");
				String[] ipInfo = info[0].split("/");
				InetAddress ip = InetAddress.getByName(ipInfo[0]);
				int mask = Integer.parseInt(ipInfo[1]);
				String location = info[1];
				CSVEntry entry = new CSVEntry();
				entry.ip = ip;
				entry.mask = mask;
				entry.location = location;
				csv_entries.add(entry);
			}
			reader.close();
		}catch(Exception e){
			System.out.println("Cannot parse csv file");
			System.exit(1);
		}
	}

	private static DatagramPacket addCSVRecord(DatagramPacket packet) throws Exception{
		DNS dns = DNS.deserialize(packet.getData(), packet.getLength());
		List<DNSResourceRecord> answers = dns.getAnswers();
		if(answers.size() == 0){
			return packet;
		}
		for(DNSResourceRecord record : answers){
			if(record.getType() == DNS.TYPE_A){
				InetAddress ip = InetAddress.getByName(record.getData().toString());
				String ipStr = ip.toString().substring(1);
				String[] ips = ipStr.split("\\.");
				//System.out.println(ips[0]);
				int ipInt = Integer.parseInt(ips[0])*256*256*256+
				Integer.parseInt(ips[1])*256*256+
				Integer.parseInt(ips[2])*256+ Integer.parseInt(ips[3]);
				for(CSVEntry entry : csv_entries){
					String entryIp = entry.ip.toString().substring(1);
					String[] entryIps = entryIp.split("\\.");
					int entryIpInt = Integer.parseInt(entryIps[0])*256*256*256+
					Integer.parseInt(entryIps[1])*256*256+
					Integer.parseInt(entryIps[2])*256+ Integer.parseInt(entryIps[3]);
					if(ipInt >> entry.mask == entryIpInt >> entry.mask){
						DNSRdata data = new DNSRdataString(entry.location + "-" + ipStr);
						DNSResourceRecord newRecord = new DNSResourceRecord(record.getName(), DNS.TYPE_EC2,data);
						dns.addAnswer(newRecord);
					}
				}
			}
		}
		byte[] buf = dns.serialize();
		packet = new DatagramPacket(buf, buf.length);
		return packet;
	}
}