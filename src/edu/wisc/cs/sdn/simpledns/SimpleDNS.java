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

	// private static DatagramPacket handle_recur(DatagramPacket packet, InetAddress server_ip) throws Exception{
	// 	DatagramPacket query_pkt = packet;
		
	// 	List<DNSResourceRecord> recordList = new ArrayList<DNSResourceRecord>();
	// 	while(true){
	// 		DatagramPacket in_pkt = recur_helper(query_pkt, server_ip, 20);
	// 		if(in_pkt != null){
	// 			DNS dns = DNS.deserialize(in_pkt.getData(), in_pkt.getLength());
	// 			List<DNSResourceRecord> answers = dns.getAnswers();
	// 			for(DNSResourceRecord record : answers){
	// 				if(record.getType() == DNS.TYPE_A || record.getType() == DNS.TYPE_AAAA){
	// 					for(DNSResourceRecord pastRecord : recordList){
	// 						dns.addAnswer(pastRecord);
	// 					}
	// 					byte[] buf = dns.serialize();
	// 					DatagramPacket returnPacket = new DatagramPacket(buf,buf.length);
	// 					return returnPacket;
	// 				}else if(record.getType() == DNS.TYPE_CNAME){
	// 					recordList.add(record);
	// 				}
	// 			}

	// 		}else{
	// 			return null;
	// 		}
	// 	}

	// }

	private static DatagramPacket recur_helper(DatagramPacket packet,InetAddress server_ip) throws Exception{

		DatagramPacket in_pkt = handle_non_recur(packet, server_ip);
		DNS dns = DNS.deserialize(in_pkt.getData(), in_pkt.getLength());
		System.out.println(dns.toString());
		if(contains_A_record(in_pkt)){
			System.out.println("Get A!!!!!!!!!!!!!!!!!!!!!!!!!");
			return in_pkt;
		}
		List<DNSResourceRecord> auths = dns.getAuthorities();
		List<DNSResourceRecord> additions = dns.getAdditional();
		if(auths.size()==0){
			return in_pkt;
		}

		DatagramPacket return_pkt = null;
		boolean found = false;
		CopyOnWriteArrayList<DNSResourceRecord> cname_records = new CopyOnWriteArrayList<DNSResourceRecord>();
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
							
						List<DNSResourceRecord> answers = get_answers(nxt_pkt);
						if(!answers.isEmpty() && !found){
						 	for(DNSResourceRecord temp_record: answers){
						 		if(temp_record.getType() == DNS.TYPE_CNAME){
									add_cname_entry(cname_records,temp_record);
						 		}
						 	}
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
								
			 				List<DNSResourceRecord> answers = get_answers(nxt_pkt);
			 				if(!answers.isEmpty()){
			 					for(DNSResourceRecord return_recs: answers){
			 						if(return_recs.getType() == DNS.TYPE_CNAME){
										add_cname_entry(cname_records,temp_record);
			 						}
			 					}
			 				}
			 			return_pkt = nxt_pkt;
			 			}
			 		}
			 	}
			 }
		}

		boolean add_cname_a_record = false;
		List<DNSResourceRecord> ARecords = new ArrayList<DNSResourceRecord>();
		if(!found && !cname_records.isEmpty()){
			
			for(DNSResourceRecord temp_r: cname_records){
				String Cname = ((DNSRdataName)temp_r.getData()).getName();
				System.out.println("Searching for new Cname:"+Cname+"+++++++++++++=");
				DatagramPacket p = construct_query(packet,Cname);
				DatagramPacket nxt_pkt = recur_helper(p, root_server_ip);
				System.out.println("stucked????");					
				if(contains_A_record(nxt_pkt)){

					add_cname_a_record = true;
					System.out.println("find confirm");
					List<DNSResourceRecord> answers = get_answers(nxt_pkt);
					if(!answers.isEmpty()){
					 	for(DNSResourceRecord temp_record: answers){
							 if(temp_record.getType() == DNS.TYPE_A){
								ARecords.add(temp_record);
							 }
						 }
					}
					break;

				}
					
				
				
			}			
		}


		if(return_pkt == null)
			return in_pkt;
		

		DNS result_dns = DNS.deserialize(return_pkt.getData(), return_pkt.getLength());
		
		for(DNSResourceRecord temp_record:cname_records){
			//if(temp_record.getType() == DNS.TYPE_CNAME){
			boolean is_dup = false;
			for(DNSResourceRecord compared_record: result_dns.getAnswers()){
				if(((DNSRdataName)temp_record.getData()).getName().equals(((DNSRdataName)compared_record.getData()).getName())){
					is_dup = true;
					break;
				}
			}
			if(!is_dup)
				result_dns.addAnswer(temp_record);
			//}	
		}
		if(add_cname_a_record){
			for(DNSResourceRecord temp_record:ARecords){
					result_dns.addAnswer(temp_record);
			}
		}
		//System.out.println(result_dns.toString());
		byte[] buf = result_dns.serialize();
		return_pkt = new DatagramPacket(buf,buf.length);
		
		
		return return_pkt;
	}

	private static void add_cname_entry(List<DNSResourceRecord> list, DNSResourceRecord record){
		System.out.println("adding cname entry+++++++++++++++++++++++++++");

		boolean dup = false;
		for(DNSResourceRecord temp: list){
			System.out.println("processing temp:"+temp.toString());
			if(((DNSRdataName)temp.getData()).getName().equals(((DNSRdataName)record.getData()).getName())){
				System.out.println("===================dup with:"+record.toString());
				return;
			}
		}
		if(!dup){
			list.add(record);
		}
		return;
	}

	private static List<DNSResourceRecord> get_answers(DatagramPacket pkt){
		DNS dns = DNS.deserialize(pkt.getData(), pkt.getLength());
		List<DNSResourceRecord> answers = dns.getAnswers();
		return answers;
	}

	

	private static boolean contains_A_record(DatagramPacket pkt) {
		List<DNSResourceRecord> answers = get_answers(pkt);
		if(answers.size()>0){
			for(DNSResourceRecord ans: answers){
				if(ans.getType()==DNS.TYPE_A){
					return true;
				}
			}	
		}
		return false;
	}

	private static DatagramPacket construct_query(DatagramPacket prev,String name){
		byte[] new_buff =  Arrays.copyOf(prev.getData(), prev.getLength());
		DNS prev_dns = DNS.deserialize(new_buff, new_buff.length);
		
		//DNSQuestion question = new DNSQuestion(name, DNS.TYPE_A);
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
				int record_ip_Int = string_to_ip(record_ip_Str);
				for(CSVEntry entry : csv_entries){
					String entry_Ip_str = entry.ip;
					int entry_Ip_Int = string_to_ip(entry_Ip_str);
					int mask = 0xffffffff << (32-entry.mask);
					System.out.println("mask ="+mask);
					System.out.println("non comparing "+record_ip_Str+"   to   "+(entry_Ip_Int&mask));
					if((record_ip_Int & mask) == (entry_Ip_Int&mask)){
						System.out.println("FInd + "+entry_Ip_str);
						DNSRdata data = new DNSRdataString(entry.location + "-" + record_ip_Str);
						DNSResourceRecord newRecord = new DNSResourceRecord(record.getName(), DNS.TYPE_EC2,data);
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
	private static int string_to_ip(String s){
		String[] ips = s.split("\\.");
		int ipInt = Integer.parseInt(ips[0])<<24+
				Integer.parseInt(ips[1])<<16+
				Integer.parseInt(ips[2])<<8 + Integer.parseInt(ips[3]);
		return ipInt;
	}
}