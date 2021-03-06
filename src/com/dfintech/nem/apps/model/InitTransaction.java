package com.dfintech.nem.apps.model;

import java.util.Date;

import org.bouncycastle.pqc.math.linearalgebra.ByteUtils;
import org.nem.core.crypto.KeyPair;
import org.nem.core.crypto.PrivateKey;
import org.nem.core.messages.PlainMessage;
import org.nem.core.model.Account;
import org.nem.core.model.Address;
import org.nem.core.model.TransactionFeeCalculatorAfterForkForApp;
import org.nem.core.model.TransferTransaction;
import org.nem.core.model.TransferTransactionAttachment;
import org.nem.core.model.mosaic.MosaicId;
import org.nem.core.model.primitive.Amount;
import org.nem.core.model.primitive.Quantity;
import org.nem.core.serialization.BinarySerializer;
import org.nem.core.time.SystemTimeProvider;
import org.nem.core.time.TimeInstant;

import com.dfintech.nem.apps.utils.Constants;
import com.dfintech.nem.apps.utils.FeeCalculateUtils;
import com.dfintech.nem.apps.utils.HexStringUtils;
import com.dfintech.nem.apps.utils.HttpClientUtils;

import net.sf.json.JSONObject;

/** 
 * @Description: Initiate a transaction
 * @author lu
 * @date 2017.03.02
 */ 
public class InitTransaction {

	private String publicKey = null;
	private String privateKey = null;
	
	public InitTransaction(String publicKey, String privateKey){
		this.publicKey = publicKey;
		this.privateKey = privateKey;
	}
	
	public String send_v1(String recipient, long amount, String messagePayload){
		//parameter object
		JSONObject params = new JSONObject();
		//inner message object
		JSONObject message = new JSONObject();
		message.put("payload", HexStringUtils.string2Hex(messagePayload));
		message.put("type", 1);
		//inner transaction object
		JSONObject transaction = new JSONObject();
		long nowTime = new Date().getTime();
		long fee = FeeCalculateUtils.calculateMinFeeNoMosaic(amount, messagePayload);
		transaction.put("timeStamp", Double.valueOf(nowTime/1000).intValue() - Constants.NEMSISTIME);
		transaction.put("amount", amount * Constants.MICRONEMS_IN_NEM);
		transaction.put("fee", fee * Constants.MICRONEMS_IN_NEM);
		transaction.put("recipient", recipient);
		transaction.put("type", 257);
		transaction.put("deadline", Double.valueOf(nowTime/1000).intValue() - Constants.NEMSISTIME + 60*60 - 1);
		transaction.put("version", 1610612737);
		transaction.put("signer", this.publicKey);
		transaction.put("message", message);
		params.put("transaction", transaction);
		params.put("privateKey", this.privateKey);
		return HttpClientUtils.post(Constants.URL_INIT_TRANSACTION, params.toString());
	}
	
	public String send_v2(String recipient, long amount, String messagePayload, MosaicId mosaicId, Quantity mosaicQuantity, String fee){
		// collect parameters
		TimeInstant timeInstant = new SystemTimeProvider().getCurrentTime();
		KeyPair senderKeyPair = new KeyPair(PrivateKey.fromHexString(this.privateKey));
		Account senderAccount = new Account(senderKeyPair);
		Account recipientAccount = new Account(Address.fromEncoded(recipient));
		// add message and mosaic
		TransferTransactionAttachment attachment = new TransferTransactionAttachment();
		if(!"".equals(messagePayload.trim())){
			PlainMessage message = new PlainMessage(messagePayload.getBytes());
			attachment.setMessage(message);
		}
		if(mosaicId!=null && mosaicQuantity!=null){
			attachment.addMosaic(mosaicId, mosaicQuantity);
		}
		if(attachment.getMessage()==null && attachment.getMosaics().size()==0){
			attachment = null;
		}
		// create transaction
		TransferTransaction transaction = new TransferTransaction(2, timeInstant, senderAccount, recipientAccount, Amount.fromNem(amount), attachment);
		// ignore fee or not
		if(fee==null){
			TransactionFeeCalculatorAfterForkForApp feeCalculator = new TransactionFeeCalculatorAfterForkForApp();
			transaction.setFee(feeCalculator.calculateMinimumFee(transaction));
		} else {
			transaction.setFee(Amount.fromNem(0));
		}
		transaction.setDeadline(timeInstant.addHours(23));
		transaction.sign();
		JSONObject params = new JSONObject();
		final byte[] data = BinarySerializer.serializeToBytes(transaction.asNonVerifiable());
		params.put("data", ByteUtils.toHexString(data));
		params.put("signature", transaction.getSignature().toString());
		return HttpClientUtils.post(Constants.URL_TRANSACTION_ANNOUNCE, params.toString());
	}
}
