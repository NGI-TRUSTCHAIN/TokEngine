package tokengine.adapter.convex;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.Ed25519Signature;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.CAIP;
import convex.core.util.Utils;
import tokengine.Engine;
import tokengine.Fields;
import tokengine.adapter.AAdapter;

/**
 * TokEngine CVM adapter
 * 
 * CVM Account Addresses are CVM Address values e.g. #12345
 */
public class CVMAdapter extends AAdapter<Address> {
	
	protected static final Logger log = LoggerFactory.getLogger(CVMAdapter.class.getName());

	protected Convex convex;
	
	private Address operatorAddress;
	
	public CVMAdapter(Engine engine, AMap<AString, ACell> nc) {
		super(engine,nc);
		operatorAddress=Address.parse(nc.get(Fields.OPERATOR_ADDRESS));

	}
	
	public static CVMAdapter build(Engine engine, AMap<AString, ACell> nc) throws IOException, TimeoutException, InterruptedException {
		CVMAdapter a= new CVMAdapter(engine,nc);
		AString chainID=RT.getIn(nc, Fields.CHAIN_ID);
		if (chainID==null) throw new IllegalArgumentException("No CVM chain ID: "+nc);
		return a;
	}
	
	private static String getHost() {
		return "localhost:18888";
	}

	public void start() throws Exception {
		convex=Convex.connect(getHost());

		// Load operator address from config
		ACell opAddrCell = RT.getIn(config, Fields.OPERATOR_ADDRESS);
		if (opAddrCell != null) {
			try {
				operatorAddress = parseAddress(opAddrCell.toString());
			} catch (Exception e) {
				log.warn("Failed to parse "+Fields.OPERATOR_ADDRESS+" from config: {}", opAddrCell);
				operatorAddress = null;
			}
		} else {
			log.warn("No operator-address specified in config for CVMAdapter");
			operatorAddress = null;
		}
		convex.setAddress(operatorAddress);
		
		// Test setup
		if (engine.isTest()) {
			// Can maybe use Engine CVM connection
			if (Utils.equals(operatorAddress,engine.getConvex().getAddress())) {
				convex=engine.getConvex();
			}
  
			long CVM_WRAP=10*1000000000l;
			Result wrapResult=convex.transactSync("(@asset.wrap.convex/wrap "+CVM_WRAP+")");
			if (wrapResult.isError()) {
				log.warn("Error wrapping Test CVM: "+wrapResult);
			} else {
				log.info("CVM test setup: wrapped CVM "+convex.querySync("(@convex.asset/balance @asset.wrap.convex *address*)"));
			}
		}
		
		if (convex.getKeyPair()==null) {
			log.warn("No CVM key pair configured, TokEngine operator cannot transact!");
		}
	}

	@Override
	public void close() {
		convex.close();
	}

	@Override
	public AMap<AString, ACell> getConfig() {
		AMap<AString, ACell> data=super.getConfig();

		return data;
	}
	
	@Override
	public AInteger getBalance(String caip19, String address) throws IOException {
		Address addr=parseAddress(address);
		
		AString aliased=tokenAliases.get(Strings.create(caip19));
		if (aliased!=null) {
			caip19=aliased.toString();
		}

		// remove chain ID if present
		String chainID=getChainIDString();
		if (caip19.startsWith(chainID)) {
			int n=chainID.length();
			if (caip19.charAt(n)!='/') {
				throw new IllegalArgumentException("Expected '/' after chain ID in "+caip19);
			}
			caip19=caip19.substring(n+1);
		}

		if ("CVM".equals(caip19)||CAIP.isCVM(caip19)) {
			try {
				Long l= convex.getBalance(parseAddress(address));
				return AInteger.create(l);
			} catch (Exception e) {
				throw new IOException(e);
			}
		} else if (caip19.startsWith("cad29")) {
			ACell tokenID=CAIP.parseTokenID(caip19);
			
			ACell qs=Reader.read("(@convex.asset/balance (quote "+tokenID+") " +addr+")");
			Result r=convex.query(qs).join();
			if (r.isError()) {
				// System.err.println(r);
				return null;
			} else {
				return r.getValue();
			}
		}
		
		throw new UnsupportedOperationException("Asset type not supported in CVMAdapter: "+caip19);
	}
	
	@Override
	public AInteger getOperatorBalance(AString caip19) throws IOException {
		if (operatorAddress==null) throw new IllegalStateException("operator address does not exist");
		return getBalance(caip19.toString(),operatorAddress.toString());
	}
	
	@Override
	public AString payout(String caip19, AInteger quantity, String destAccount) throws Exception {
		Address addr=parseAddress(destAccount);
		Result r;
		
		AString aliased=tokenAliases.get(Strings.create(caip19));
		if (aliased!=null) {
			caip19=aliased.toString();
		}
		
		// remove chain ID if present
		String chainID=getChainIDString();
		if (caip19.startsWith(chainID)) {
			int n=chainID.length();
			if (caip19.charAt(n)!='/') {
				throw new IllegalArgumentException("Expected '/' after chain ID in "+caip19);
			}
			caip19=caip19.substring(n+1);
		}
		
		if ("CVM".equals(caip19)||CAIP.isCVM(caip19)) {
			if (!quantity.isLong()) {
				throw new IllegalArgumentException("Invalid CVM quantity: "+quantity);
			}
			r= convex.transferSync(addr, quantity.longValue());

		} else {
			ACell tokenID=CAIP.parseTokenID(caip19);
			r = convex.transactSync("(let [quantity "+quantity+"] (@convex.asset/transfer "+addr+" [(quote "+tokenID+") quantity]) quantity)");
		}
		
		if (r.isError()) {
			throw new Exception("Payout on "+getChainID()+" failed: "+r);
		} else {
			ACell tx=RT.getIn(r, Keywords.INFO, Keywords.TX);
			return RT.str(tx);
		}
	}
	
	@Override
	public Address parseAddress(String caip10) throws IllegalArgumentException {
		if (caip10 == null) throw new IllegalArgumentException("Null address");
		String s= caip10.trim();
		if (s.isEmpty()) throw new IllegalArgumentException("Empty address");

		int colon=s.lastIndexOf(":");
		if (colon>=0) {
			if (!s.startsWith(getChainIDString())) throw new IllegalArgumentException("Wrong chain ID for this adapter: "+s);
			s=s.substring(colon+1); // take the part after the colon
		}

		// Accept non-negative integer as valid address
		try {
			long l = Long.parseLong(s);
			if (l < 0) throw new IllegalArgumentException("Negative address not allowed: " + caip10);
			return Address.create(l);
		} catch (NumberFormatException e) {
			// Not a plain integer, fall through
		}

		// Accept #12345 format
		if (!s.startsWith("#")) {
			throw new IllegalArgumentException("Invalid address format - must be non-negative integer or start with #: " + caip10);
		}
		Address result = Address.parse(s);
		if (result == null) {
			throw new IllegalArgumentException("Invalid address format: " + caip10);
		}
		return result;
	}

	@Override
	public Address parseAddress(Object obj) throws IllegalArgumentException {
		if (obj == null) throw new IllegalArgumentException("Null address");
		if (obj instanceof Address addr) {
			return addr;
		}
		if (obj instanceof AString as) {
			Address a=Address.parse(as);
			if (a==null) throw new IllegalArgumentException("Bad Convex address format");
			return a;
		}
		if (obj instanceof String s) {
			return parseAddress(s);
		}
		throw new IllegalArgumentException("Cannot parse address from object: " + obj.getClass());
	}
	
	@Override
	public AString parseUserKey(String address) throws IllegalArgumentException {
		Address a=parseAddress(address);
		return a.toCVMString(10);
	}

	
	@Override
	public ACell parseAssetID(String assetID) {
		return CAIP.parseAssetID(Strings.create(assetID.toLowerCase()));
	}
	
	@Override
	public AString toCAIPAssetID(ACell asset) {
		if (CAIP.CONVEX_ASSET_ID.equals(asset)) return CAIP.CONVEX_ASSET_ID;
		return Strings.create(CAIP.toAssetID(asset));
	}

	public Convex getConvex() {
		return convex;
	}
	
	@Override
	public AString getDescription() {
		AString desc=super.getDescription();
		if (desc==null) return Strings.create("Undescribed Convex Network at "+getHost());
		return desc;
	}

	@Override
	public Address getOperatorAddress() {
		if (operatorAddress == null) {
			log.warn("operatorAddress is null in getOperatorAddress()");
		}
		return operatorAddress;
	}

	@Override
	public boolean verifyPersonalSignature(String messageText, String signature, String publicKey) {
		AccountKey pk=AccountKey.parse(publicKey);
		if (pk==null) throw new IllegalArgumentException("Invalid Convex account key: "+publicKey);
		
		Blob sigData=Blob.parse(signature);
		if (sigData==null) throw new IllegalArgumentException("Invalid signature data: "+signature);
		
		AString msg=Strings.create(messageText);
		if (msg==null) throw new IllegalArgumentException("Invalid message: "+msg);
		
		Ed25519Signature sig = Ed25519Signature.wrap(sigData.getBytes());
		
		return sig.verify(msg.toFlatBlob(), pk);
	}

	@Override
	public AInteger checkTransaction(String address, String tokenID, Blob tx) {
		Result tr =engine.getPeer().getTransactionResult(tx);
		if (tr==null) return null; // transaction not found
		ACell aid=parseAssetID(tokenID);

		AInteger dep=tr.getLog().reduce( new BiFunction<>() {
			@Override
			public AInteger apply(AInteger acc, AVector<ACell> logRec) {
				AVector<ACell> v=RT.ensureVector(logRec.get(3));
				if (v==null) return acc;
				if (v.count()<4) return acc; // doesn't look like transfer event
				
				if(!checkScopedAddress(aid,v.get(1),v.get(2)))
				
				if (!Fields.TR.equals(v.get(0))) return acc;
				AInteger amt=RT.ensureInteger(v.get(3));
				if (amt==null) return acc;
				return acc.add(amt);
			}

		},(AInteger)CVMLong.ZERO);
		
		return dep;
	}
	
	private boolean checkScopedAddress(ACell assetID, ACell addr, ACell scope) {
		if (scope==null) return Utils.equals(assetID, addr); // Like #1378
		return Utils.equals(assetID, Vectors.of(addr,scope));
	}

	@Override
	public Blob parseTransactionID(AString tx) {
		Blob b=Blob.parse(tx.toString());
		if (b.count()!=32) return null;
		return b;
	}

	@Override
	public Address getReceiverAddress() {
		Address result= Address.parse(RT.getIn(config, Fields.RECEIVER_ADDRESS));
		// if (result==null) result =getOperatorAddress();
		return result;
	}

	@Override
	protected ACell deployTestAsset(AMap<AString, ACell> tnet)  {
		AInteger decimals=RT.ensureInteger(RT.getIn(tnet, Fields.DECIMALS));
		if (decimals==null) decimals = CVMLong.ZERO; // TODO: look up standard token decimals?
		ACell code=Reader.read("(deploy (@convex.fungible/build-token {:supply 1000000000000 :decimals "+decimals+"}))");
		Result r;
		try {
			r = engine.getConvex().transactSync(code);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Unable to deploy test asset due to interrupt");
		}
		if (r.isError()) {
			throw new IllegalStateException("Unable to deploy test asset",new ResultException(r));
		} else {
			return r.getValue();
		}
	}

	@Override
	public boolean validateSignature(String userKey, ABlob signature, ABlob message) {
		try {
			Address addr=parseAddress(userKey);
			if (addr==null) return false;
		
			AccountKey key=RT.ensureAccountKey(convex.querySync("(get (account "+addr+") :key)").getValue());
			if (key==null) return false; // not a user account 
			
			Ed25519Signature sig=Ed25519Signature.wrap(signature.getBytes());
			return sig.verify(message.toFlatBlob(), key);
		} catch (InterruptedException e) {
			return false;
		}
	}

}
