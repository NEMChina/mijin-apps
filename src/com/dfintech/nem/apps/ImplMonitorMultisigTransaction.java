package com.dfintech.nem.apps;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.dfintech.nem.apps.model.OutgoingTransaction;
import com.dfintech.nem.apps.model.UnconfirmedTransaction;
import com.dfintech.nem.apps.utils.Constants;
import com.dfintech.nem.apps.utils.HelperUtils;
import com.dfintech.nem.apps.utils.HexStringUtils;
import com.dfintech.nem.apps.utils.HttpClientUtils;
import com.dfintech.nem.apps.utils.OutputMessage;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** 
 * @Description: Main class - test monitor multisig transactions
 * @author lu
 * @date 2017.03.07
 */ 
public class ImplMonitorMultisigTransaction {

	private static long lastID = 0;
	
	private static Map<String, Integer> cosignMap = null;
	
	private static String address = null;
	
	public static void main(String[] args) {
		if(args.length==0){
			OutputMessage.error("please enter parameter");
			return;
		}
		Map<String, String> params = parseParamsToMap(args);
		if(params==null){
			return;
		}
		String host = params.get("host");
		String port = params.get("port");
		// set host and port
		if(host!=null)
			HttpClientUtils.defaultHost = host;
		if(port!=null)
			HttpClientUtils.defaultPort = port;
		address = params.get("address");
		ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);
		pool.scheduleWithFixedDelay(new Runnable(){
			public void run() {
				lastID = monitorOutgoing(address, lastID);
				monitorUnconfirmed(address);
			};
		}, 0 * 1000, 10 * 1000, TimeUnit.MILLISECONDS);
	}
	
	/**
	 * parse parameters
	 * @param args
	 * @return
	 */
	private static Map<String, String> parseParamsToMap(String[] args){
		Map<String, String> params = new HashMap<String, String>();
		CommandLineParser parser = new DefaultParser();
		Options options = new Options();
		options.addOption(Option.builder("address").hasArg().build());
		options.addOption(Option.builder("host").hasArg().build());
		options.addOption(Option.builder("port").hasArg().build());
		options.addOption(Option.builder("h").longOpt("help").build());
		CommandLine commandLine = null;
		try{
			commandLine = parser.parse(options, args);
		} catch(Exception ex) {
			OutputMessage.error("invalid parameter");
			return null;
		}
		// print helper
		if(commandLine.hasOption("h")){
			System.out.println(HelperUtils.printHelper(Constants.HELPER_FILE_MONITOR_MULTISIG_TRANSACTION));
			return null;
		}
		String address = commandLine.getOptionValue("address")==null?"":commandLine.getOptionValue("address").replaceAll("-", "");
		String host = commandLine.getOptionValue("host");
		String port = commandLine.getOptionValue("port");
		// check address
		if(address.length()!=40){
			OutputMessage.error("invalid parameter [address]");
			return null;
		}
		// check host
		if(host!=null && !host.matches("[0-9a-zA-Z]+(\\.[0-9a-zA-Z]+)+")){
			OutputMessage.error("invalid parameter [host]");
			return null;
		}
		// check port
		if(port!=null && !port.matches("[0-9]{1,5}")){
			OutputMessage.error("invalid parameter [port]");
			return null;
		}
		params.put("address", address);
		params.put("host", host);
		params.put("port", port);
		return params;
	}
	
	/**
	 * monitor outgoing transactions
	 * @param address
	 * @param lastID
	 * @return
	 */
	private static long monitorOutgoing(String address, long lastID){
		long newLastID = 0;
		long queryID = 0;
		// outgoing multisig transactions
		while(true){
			OutgoingTransaction tx = new OutgoingTransaction(address);
			String result = tx.query(queryID);
			JSONObject json = null;
			try {
				json = JSONObject.fromObject(result);
			} catch (Exception ex) {
				return lastID;
			}
			DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			JSONArray array = json.getJSONArray("data");
			if(array.size()==0){
				return newLastID;
			}
			for(int i=0;i<array.size();i++){
				JSONObject item = array.getJSONObject(i);
				JSONObject meta = item.getJSONObject("meta");
				JSONObject transaction = item.getJSONObject("transaction");
				if(lastID==0){ //init (first time)
					return meta.getLong("id");
				} else { //monitor
					if(newLastID==0){
						newLastID = meta.getLong("id");
					}
					if(meta.getLong("id")<=lastID){
						return newLastID;
					}
					queryID = meta.getLong("id");
					if(transaction.containsKey("signatures")){ //multisig transaction
						JSONObject outJSON = new JSONObject();
						JSONArray outCosignAccountArray = new JSONArray();
						JSONObject otherTrans = transaction.getJSONObject("otherTrans");
						JSONArray signatures = transaction.getJSONArray("signatures");
						JSONObject outCosignAccount = new JSONObject();
						//query all cosign account
						Set<String> allCosignAccount = new HashSet<String>();
						int minCosignatories = queryAllCosignAccount(address, allCosignAccount);
						//cosigned account
						String signer = transaction.getString("signer");
						String queryResult = HttpClientUtils.get(Constants.URL_ACCOUNT_GET_FROMPUBLICKEY + "?publicKey=" + signer);
						JSONObject queryAccount = JSONObject.fromObject(queryResult);
						outCosignAccount.put("address", queryAccount.getJSONObject("account").getString("address"));
						outCosignAccount.put("date", dateFormat.format(new Date((transaction.getLong("timeStamp") + Constants.NEMSISTIME)*1000)));
						outCosignAccountArray.add(outCosignAccount);
						allCosignAccount.remove(queryAccount.getJSONObject("account").getString("address"));
						for(int j=0;j<signatures.size();j++){
							JSONObject signature = signatures.getJSONObject(j);
							outCosignAccount = new JSONObject();
							signer = signature.getString("signer");
							queryResult = HttpClientUtils.get(Constants.URL_ACCOUNT_GET_FROMPUBLICKEY + "?publicKey=" + signer);
							queryAccount = JSONObject.fromObject(queryResult);
							outCosignAccount.put("address", queryAccount.getJSONObject("account").getString("address"));
							outCosignAccount.put("date", dateFormat.format(new Date((signature.getLong("timeStamp") + Constants.NEMSISTIME)*1000)));
							outCosignAccountArray.add(outCosignAccount);
							allCosignAccount.remove(queryAccount.getJSONObject("account").getString("address"));
						}
						//unsigned account
						JSONArray outUnsignedAccount = new JSONArray();
						for(String unsignedAccount:allCosignAccount){
							JSONObject unsignedAccountItem = new JSONObject();
							unsignedAccountItem.put("address", unsignedAccount);
							outUnsignedAccount.add(unsignedAccountItem);
						}
						outJSON.put("status", "success");
						outJSON.put("innerTransactionHash", meta.getJSONObject("innerHash").getString("data"));
						outJSON.put("cosignAccount", outCosignAccountArray);
						outJSON.put("unsignedAccount", outUnsignedAccount);
						outJSON.put("minCosignatories", minCosignatories);
						outJSON.put("recipient", otherTrans.getString("recipient"));
						outJSON.put("amount", Math.round((otherTrans.getLong("amount") / Math.pow(10, 6))));
						// message 
						if(otherTrans.containsKey("message") && otherTrans.getJSONObject("message").containsKey("type")){
							JSONObject message = otherTrans.getJSONObject("message");
							// if message type is 1, convert to String
							if(message.getInt("type")==1 && HexStringUtils.hex2String(message.getString("payload"))!=null){
								outJSON.put("message", HexStringUtils.hex2String(message.getString("payload")));
							}
						}
						System.out.println(outJSON.toString());
					}
				}
			}
		}
	}
	
	/**
	 * monitor unconfirmed transactions
	 * @param address
	 */
	private static void monitorUnconfirmed(String address){
		UnconfirmedTransaction tx = new UnconfirmedTransaction(address);
		String result = tx.query();
		JSONObject json = null;
		try {
			json = JSONObject.fromObject(result);
		} catch (Exception ex) {
			return;
		}
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		JSONArray array = json.getJSONArray("data");
		if(cosignMap==null){ //init
			cosignMap = new HashMap<String, Integer>();
			for(int i=0;i<array.size();i++){
				JSONObject item = array.getJSONObject(i);
				JSONObject meta = item.getJSONObject("meta");
				JSONObject transaction = item.getJSONObject("transaction");
				if(transaction.containsKey("signatures")){
					cosignMap.put(meta.getString("data"), transaction.getJSONArray("signatures").size()+1);
				}
			}
		} else {
			for(int i=0;i<array.size();i++){
				JSONObject item = array.getJSONObject(i);
				JSONObject meta = item.getJSONObject("meta");
				JSONObject transaction = item.getJSONObject("transaction");
				if(!transaction.containsKey("signatures")){
					continue;
				}
				JSONObject otherTrans = transaction.getJSONObject("otherTrans");
				JSONArray signatures = transaction.getJSONArray("signatures");
				if(cosignMap.containsKey(meta.getString("data")) && cosignMap.get(meta.getString("data"))>signatures.size()){
					continue;
				}
				//query all cosign account
				Set<String> allCosignAccount = new HashSet<String>();
				int minCosignatories = queryAllCosignAccount(address, allCosignAccount);
				if(signatures.size()+1==minCosignatories){
					continue;
				}
				JSONObject outJSON = new JSONObject();
				JSONArray outCosignAccount = new JSONArray();
				JSONObject cosignAccountItem = new JSONObject();
				//cosigned account
				String signer = transaction.getString("signer");
				String queryResult = HttpClientUtils.get(Constants.URL_ACCOUNT_GET_FROMPUBLICKEY + "?publicKey=" + signer);
				JSONObject queryAccount = JSONObject.fromObject(queryResult);
				cosignAccountItem.put("address", queryAccount.getJSONObject("account").getString("address"));
				cosignAccountItem.put("date", dateFormat.format(new Date((otherTrans.getLong("timeStamp") + Constants.NEMSISTIME)*1000)));
				outCosignAccount.add(cosignAccountItem);
				allCosignAccount.remove(queryAccount.getJSONObject("account").getString("address"));
				for(int j=0;j<signatures.size();j++){
					JSONObject signature = signatures.getJSONObject(j);
					item = new JSONObject();
					signer = signature.getString("signer");
					queryResult = HttpClientUtils.get(Constants.URL_ACCOUNT_GET_FROMPUBLICKEY + "?publicKey=" + signer);
					queryAccount = JSONObject.fromObject(queryResult);
					cosignAccountItem.put("address", queryAccount.getJSONObject("account").getString("address"));
					cosignAccountItem.put("date", dateFormat.format(new Date((signature.getLong("timeStamp") + Constants.NEMSISTIME)*1000)));
					outCosignAccount.add(cosignAccountItem);
					allCosignAccount.remove(queryAccount.getJSONObject("account").getString("address"));
				}
				//unsigned account
				JSONArray outUnsignedAccount = new JSONArray();
				for(String unsignedAccount:allCosignAccount){
					JSONObject unsignedAccountItem = new JSONObject();
					unsignedAccountItem.put("address", unsignedAccount);
					outUnsignedAccount.add(unsignedAccountItem);
				}
				outJSON.put("status", "pending");
				outJSON.put("innerTransactionHash", meta.getString("data"));
				outJSON.put("cosignAccount", outCosignAccount);
				outJSON.put("unsignedAccount", outUnsignedAccount);
				outJSON.put("minCosignatories", minCosignatories);
				outJSON.put("recipient", otherTrans.getString("recipient"));
				outJSON.put("amount", Math.round((otherTrans.getLong("amount") / Math.pow(10, 6))));
				// message 
				if(otherTrans.containsKey("message") && otherTrans.getJSONObject("message").containsKey("type")){
					JSONObject message = otherTrans.getJSONObject("message");
					// if message type is 1, convert to String
					if(message.getInt("type")==1 && HexStringUtils.hex2String(message.getString("payload"))!=null){
						outJSON.put("message", HexStringUtils.hex2String(message.getString("payload")));
					}
				}
				cosignMap.put(meta.getString("data"), signatures.size()+1);
				System.out.println(outJSON.toString());
			}
		}
	}
	
	/**
	 * query all cosignatories
	 * @param multisigAccount
	 * @param allCosignAccount
	 * @return
	 */
	private static int queryAllCosignAccount(String multisigAccount, Set<String> allCosignAccount){
		String queryResult = HttpClientUtils.get(Constants.URL_ACCOUNT_GET + "?address=" + multisigAccount);
		JSONObject queryAccount = JSONObject.fromObject(queryResult);
		if(queryAccount.containsKey("meta") && queryAccount.getJSONObject("meta").containsKey("cosignatories")){
			JSONArray cosignatories = queryAccount.getJSONObject("meta").getJSONArray("cosignatories");
			for(int i=0;i<cosignatories.size();i++){
				allCosignAccount.add(cosignatories.getJSONObject(i).getString("address"));
			}
		}
		return queryAccount.getJSONObject("account").getJSONObject("multisigInfo").getInt("minCosignatories");
	}
}
