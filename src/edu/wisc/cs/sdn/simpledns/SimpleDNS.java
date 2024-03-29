package edu.wisc.cs.sdn.simpledns;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.RecursiveAction;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdata;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataAddress;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataName;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataString;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;

class CSVEntry
{
	String ip;
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
				if(dns.getOpcode() != DNS.OPCODE_STANDARD_QUERY){
					continue;
				}
				//get query 
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
					receive_pkt = recur_helper(packet,root_server_ip);
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


	private static DatagramPacket recur_helper(DatagramPacket packet,InetAddress server_ip) throws Exception{

		DatagramPacket in_pkt = handle_non_recur(packet, server_ip);
		DNS dns = DNS.deserialize(in_pkt.getData(), in_pkt.getLength());
		System.out.println(dns.toString());
		if(contains_A_record(in_pkt)){
			System.out.println("Get A!!!!!!!!!!!!!!!!!!!!!!!!!");
			return in_pkt;
		}
		if(contians_CNAME_record(in_pkt)){
			List<DNSResourceRecord> c_records = get_CNAME_records(in_pkt);
			DNSResourceRecord c_record = c_records.get(0);
			String Cname = ((DNSRdataName)c_record.getData()).getName();
			DatagramPacket p = construct_query(packet,Cname);
			DatagramPacket nxt_pkt = recur_helper(p, root_server_ip);
			DNS nxt_dns = DNS.deserialize(in_pkt.getData(), in_pkt.getLength());
			List<DNSResourceRecord> ans_s = new ArrayList<DNSResourceRecord>();
			
			if(contains_A_record(nxt_pkt)){
				List<DNSResourceRecord> answers = get_answers(nxt_pkt);
		 			 	for(DNSResourceRecord temp_record: answers){
							boolean is_dup = false;
							for(DNSResourceRecord compared_record: dns.getAnswers()){
								if(temp_record.getType() == DNS.TYPE_CNAME && ((DNSRdataName)temp_record.getData()).getName().equals(((DNSRdataName)compared_record.getData()).getName())){
									is_dup = true;
									break;
								}
							}
							if(!is_dup)
							nxt_dns.addAnswer(temp_record);
		 				 }
				}
		
			byte[] buf = nxt_dns.serialize();
			return new DatagramPacket(buf,buf.length);

		}
		List<DNSResourceRecord> auths = dns.getAuthorities();
		List<DNSResourceRecord> additions = dns.getAdditional();
		if(auths.size()==0){
			return in_pkt;
		}

		DatagramPacket return_pkt = null;
		boolean found = false;
		//CopyOnWriteArrayList<DNSResourceRecord> cname_records = new CopyOnWriteArrayList<DNSResourceRecord>();
		for(DNSResourceRecord auth_entry: auths){
			if(found == true)
				break;
			
			if(auth_entry.getType() != DNS.TYPE_NS)
				continue;
			
			String auth_name = ((DNSRdataName)auth_entry.getData()).toString();
			System.out.println("processing auth:"+auth_name);
			boolean matches = false;
			InetAddress nxt_server = null;
			
			for(DNSResourceRecord add_entry: additions){
				if(add_entry.getType()!=DNS.TYPE_A)
					continue;
				
				String add_name = add_entry.getName();
				if(auth_name.equals(add_name)){
						matches = true;
						nxt_server = ((DNSRdataAddress)add_entry.getData()).getAddress();
						DatagramPacket nxt_pkt= recur_helper(packet,nxt_server);
						if(contains_A_record(nxt_pkt)){
							found = true;
							System.out.println("find confirm");
						}
						return_pkt = nxt_pkt;
				}
			}
			if(!matches){
				DatagramPacket quesr_on_auth_packet = construct_query(packet,auth_name);
			 	DatagramPacket recur_on_auth = recur_helper(quesr_on_auth_packet, root_server_ip);
			 	if(contains_A_record(recur_on_auth)){
			 		for(DNSResourceRecord temp_record: get_answers(recur_on_auth)){
			 			if(temp_record.getType() == DNS.TYPE_A){
			 				nxt_server = ((DNSRdataAddress)temp_record.getData()).getAddress();
			 				DatagramPacket nxt_pkt= recur_helper(packet,nxt_server);
			 				if(contains_A_record(nxt_pkt)){
			 					found = true;
			 					System.out.println("find confirm");
			 				}
			 			return_pkt = nxt_pkt;
			 			}
			 		}
			 	}
			 }
		}
		if(return_pkt == null)
			return in_pkt;
		

		DNS result_dns = DNS.deserialize(return_pkt.getData(), return_pkt.getLength());
		byte[] buf = result_dns.serialize();
		return_pkt = new DatagramPacket(buf,buf.length);
		
		
		return return_pkt;
	}

	// private static void add_cname_entry(List<DNSResourceRecord> list, DNSResourceRecord record){
	// 	System.out.println("adding cname entry+++++++++++++++++++++++++++");

	// 	boolean dup = false;
	// 	for(DNSResourceRecord temp: list){
	// 		System.out.println("processing temp:"+temp.toString());
	// 		if(((DNSRdataName)temp.getData()).getName().equals(((DNSRdataName)record.getData()).getName())){
	// 			return;
	// 		}
	// 	}
	// 	if(!dup){
	// 		list.add(record);
	// 	}
	// 	return;
	// }

	private static List<DNSResourceRecord> get_answers(DatagramPacket pkt){
		DNS dns = DNS.deserialize(pkt.getData(), pkt.getLength());
		List<DNSResourceRecord> answers = dns.getAnswers();
		return answers;
	}

	private static List<DNSResourceRecord> get_CNAME_records(DatagramPacket pkt){
		List<DNSResourceRecord> answers = get_answers(pkt);
		List<DNSResourceRecord> c_records = new ArrayList<DNSResourceRecord>();
		for(DNSResourceRecord rec: answers){
			if(rec.getType()==DNS.TYPE_CNAME)
				c_records.add(rec);
		}
		return c_records;
	}

	private static boolean contians_CNAME_record(DatagramPacket pkt){
		return get_CNAME_records(pkt).size()>0;
	}

	private static boolean contains_A_record(DatagramPacket pkt) {
		List<DNSResourceRecord> answers = get_answers(pkt);
		if(answers.size()>0){
			for(DNSResourceRecord ans: answers){
				if(ans.getType()==DNS.TYPE_A || ans.getType()==DNS.TYPE_AAAA){
					return true;
				}
			}	
		}
		return false;
	}

	private static DatagramPacket construct_query(DatagramPacket prev,String name){
		byte[] new_buff =  Arrays.copyOf(prev.getData(), prev.getLength());
		DNS prev_dns = DNS.deserialize(new_buff, new_buff.length);
		List<DNSQuestion> questions = prev_dns.getQuestions();
		DNSQuestion question = questions.get(0);
		question.setName(name);
		List<DNSQuestion> modified_questions = new ArrayList<DNSQuestion>();
		modified_questions.add(question);
		prev_dns.setQuestions(modified_questions);
		return new DatagramPacket(prev_dns.serialize(), prev_dns.getLength());
	
	}

	private static void parse_csv(String filename){
		csv_entries = new ArrayList<CSVEntry>();
		try{
			BufferedReader reader  = new BufferedReader(new FileReader(filename));
			String line = null;
			while((line=reader.readLine())!=null){
				String[] info = line.split(",");
				String[] ipInfo = info[0].split("/");
				String ip = ipInfo[0];
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
		List<DNSResourceRecord> txts = new ArrayList<DNSResourceRecord>();
		for(DNSResourceRecord record : answers){
			if(record.getType() == DNS.TYPE_A){
				DNSRdataAddress record_ip = (DNSRdataAddress)record.getData();
				String record_ip_Str = record_ip.getAddress().toString().substring(1);
				//System.out.println(ips[0]);
				long record_ip_Int = string_to_ip(record_ip_Str);
				for(CSVEntry entry : csv_entries){
					String entry_Ip_str = entry.ip;
					long entry_Ip_Int = string_to_ip(entry_Ip_str);
					long mask = 0x00000000ffffffff << (32-entry.mask);
					if((record_ip_Int & mask) == (entry_Ip_Int&mask)){
						DNSRdata data = new DNSRdataString(entry.location + "-" + record_ip_Str);
						DNSResourceRecord newRecord = new DNSResourceRecord(record.getName(), (short)16,data);
						txts.add(newRecord);
					}
				}
			}
		}
		for(DNSResourceRecord txt_record: txts){
			dns.addAnswer(txt_record);
		}
		byte[] buf = dns.serialize();
		packet = new DatagramPacket(buf, buf.length);
		return packet;
	}
	public static long string_to_ip(String ipAddress) {		
		long result = 0;			
		String[] ipAddressInArray = ipAddress.split("\\.");	
		for (int i = 3; i >= 0; i--) {				
			long ip = Long.parseLong(ipAddressInArray[3 - i]);
			result |= ip << (i * 8);			
		}	
		return result;
	  }
}