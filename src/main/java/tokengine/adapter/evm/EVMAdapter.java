package tokengine.adapter.evm;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.DefaultGasProvider;

import convex.core.crypto.Hashing;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.FileUtils;
import tokengine.Engine;
import tokengine.Fields;
import tokengine.adapter.AAdapter;

/**
 * TokeEngine EVM adapter
 * 
 * Canonical EVM Addresses are AStrings of the form "0xab16a96d359ec26a11e2c2b3d8f8B8942d5bfcdb" (lowercase, leading 0x)
 */
public class EVMAdapter extends AAdapter<AString> {
	
	protected static final Logger log = LoggerFactory.getLogger(EVMAdapter.class.getName());
	
    public static final Event TRANSFER_EVENT = new Event("Transfer",
            Arrays.asList(
                    new TypeReference<org.web3j.abi.datatypes.Address>(true) {}, // from (indexed)
                    new TypeReference<org.web3j.abi.datatypes.Address>(true) {}, // to (indexed)
                    new TypeReference<org.web3j.abi.datatypes.Uint>(false) {} // value (non-indexed)
            ));

    public static final String TRANSFER_SIGNATURE = EventEncoder.encode(TRANSFER_EVENT);
    
    public static final AString ETH_ASSET_ID=Strings.intern("slip44:60");

	private AString operatorAddress;
	private AString receiverAddress;

	protected EVMAdapter(Engine engine, AMap<AString, ACell> nc) {
		super(engine,nc);
		
		// Load operator address from config
		ACell opAddrCell = RT.getIn(config, Fields.OPERATOR_ADDRESS);
		if (opAddrCell != null) {
			try {
				operatorAddress = parseAddress(opAddrCell.toString());
			} catch (Exception e) {
				log.warn("Failed to parse {} from config: {}", Fields.OPERATOR_ADDRESS, opAddrCell);
				operatorAddress = null;
			}
		} 
	}

	Web3j web3;
	List<Credentials> loadedWallets = new ArrayList<>();
	
	public static EVMAdapter build(Engine engine, AMap<AString, ACell> nc) {
		EVMAdapter a= new EVMAdapter(engine, nc);
		AString chainID=RT.getIn(nc, Fields.CHAIN_ID);
		if (chainID==null) throw new IllegalArgumentException("No EVM chain ID: "+nc);
		return a;
	}

	
	@Override
	public void start() {
		if (operatorAddress==null) {
			log.warn("No operatorAddress specified in config for EVMAdapter");
		}
		
		AString url=RT.getIn(config, Fields.URL);
		if (url==null) throw new IllegalStateException("No Ethereum RPC ndoe specified, should be in networks[..].url");
		web3 = Web3j.build(new HttpService(url.toString()));



		// Load wallets from config-specified directory
		try {
			loadWalletsFromConfig();
		} catch (Exception e) {
			log.warn("Failed to load wallets from config directory: " + e.getMessage());
		}
	}

	@Override
	public void close() {
	}

	@Override 
	public AInteger getBalance(String asset, String address) throws IOException {
		if (isEth(asset)) {
			EthGetBalance balanceResponse = getWeb3().ethGetBalance(address, DefaultBlockParameterName.LATEST).send(); 
			if (balanceResponse.hasError()) {
				throw new IllegalStateException("Can't get ETH balance");
			} else {
				BigInteger bal=balanceResponse.getBalance();
				return AInteger.create(bal);
			}
		} else if (asset.startsWith("erc20")) {
			String contractAddress=asset.substring(6); // skip 'erc20:'
			
			Credentials cred=Credentials.create("0x0", address); // 0x0 = no private key, not needed
			
			ERC20 contract = ERC20.load(contractAddress, getWeb3(), cred, new DefaultGasProvider());
			try {
				BigInteger bi=contract.balanceOf(address).send();
				return AInteger.create(bi);
			} catch (Exception e) {
				throw new IOException("Failure getting ERC20 balance",e);
			}
		}
		
		throw new UnsupportedOperationException("Asset not supported in EVMAdapter: "+asset);
	}
	

	@Override
	public AInteger getOperatorBalance(AString asset) throws IOException {
		return getBalance(asset.toString(),getOperatorAddress().toString());
	}

	private boolean isEth(String asset) {
		if ("ETH".equals(asset)) return true;
		return "slip44:60".equals(asset);
	}

	@Override
	public AString parseAddress(String caip10) throws IllegalArgumentException {
		if (caip10 == null) return null;
		String s = caip10.trim();
		if (s.isEmpty()) throw new IllegalArgumentException("Empty address");
		
		int colon=s.lastIndexOf(":");
		if (colon>=0) {
			if (!s.startsWith(getChainIDString())) throw new IllegalArgumentException("Wrong chain ID for this adapter: "+s);
			s=s.substring(colon+1); // take the part after the colon
		}
	
		if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
		s = s.toLowerCase();
		if (s.length() != 40) throw new IllegalArgumentException("Invalid hex length for EVM Adapter: " + caip10);
		// Validate hex
		if (!s.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid hex address for EVM Adapter: " + caip10);
		return convex.core.data.Strings.create(s);
	}
	
	@Override
	public ACell parseAssetID(String assetID) {
		assetID=assetID.toLowerCase();
		if (assetID.startsWith("erc20:")) {
			return parseERC20TokenID(assetID);
		} else if (Strings.create(assetID).equals(ETH_ASSET_ID)){
			return ETH_ASSET_ID; // "slip44:60"
		}
		return null;
	}
	
	@Override
	public AString toCAIPAssetID(ACell asset) {
		if (ETH_ASSET_ID.equals(asset)) return ETH_ASSET_ID;
		if (asset instanceof AString s) {
			if (!s.startsWith("0x")) {
				s=Strings.create("0x"+s);
			}
			return Strings.create("erc20:"+s);
		}
		return null;
	}
	
	private AString parseERC20TokenID(String tokenID) {
		tokenID=tokenID.toLowerCase();
		if (tokenID.startsWith("erc20:")) {
			// rest of ID should be an account address
			return parseAddress(tokenID.substring(6));
		}
		throw new IllegalArgumentException("Invalid CAIP-19 tokenID: "+tokenID);
	}
	
	@Override
	public AString parseAddress(Object obj) throws IllegalArgumentException {
		if (obj == null) return null;
		if (obj instanceof AString) {
			// Normalise the AString by parsing it as a string
			return parseAddress(obj.toString());
		}
		if (obj instanceof ACell) {
			// If it's an ACell but not the right type, try toString
			return parseAddress(obj.toString());
		}
		if (obj instanceof String) {
			return parseAddress((String)obj);
		}
		return null;
	}

	@Override
	public AString payout(String token, AInteger quantity, String destAccount) throws Exception {
		try {
			AString tokenS=parseAddress(token);
			AString destS=parseAddress(destAccount);
	        // Validate inputs
	        if (tokenS == null ) {
	            throw new IllegalArgumentException("Invalid token contract address");
	        }
	        if (destS == null ) {
	        	throw new IllegalArgumentException("Invalid destination account address: "+destAccount);
	        }
	        if (quantity == null || quantity.isNegative()) {
	        	throw new IllegalArgumentException("Invalid quantity: "+quantity);
	        }

	        Credentials credentials = getOperatorCredentials();

	        // Load the ERC20 contract
	        ERC20 contract = ERC20.load(
	            token, // Token contract address
	            web3,
	            credentials,
	            new DefaultGasProvider()
	        );

	        // Convert quantity to BigInteger 
	        BigInteger amount = quantity.big();

	        // Check balance
	        BigInteger balance = contract.balanceOf(credentials.getAddress()).send();
	        if (balance.compareTo(amount) < 0) {
	        	throw new IllegalStateException("Insufficient token balance");
	        }

	        // Check allowance if needed (for contracts requiring approval)
	        BigInteger allowance = contract.allowance(
	            credentials.getAddress(),
	            destAccount
	        ).send();
	        if (allowance.compareTo(amount) < 0) {
	            // If allowance is insufficient, you might need to approve first
	            TransactionReceipt approvalReceipt = contract.approve(
	                destAccount,
	                amount
	            ).send();
	            if (!approvalReceipt.isStatusOK()) {
	            	throw new IllegalArgumentException("Approval transaction failed");
	            }
	        }

	        // Execute transfer
	        TransactionReceipt receipt = contract.transfer(
	            destAccount,
	            amount
	        ).send();

	        // Verify transaction status
	        if (receipt.isStatusOK()) {
	            return Strings.create(receipt.getTransactionHash());
	        } else {
	        	throw new IllegalArgumentException("Transfer transaction failed: "+receipt);
	        }
	    } finally {
	    	//
	    }
	}

	/** Store for operator credentials once found */
	private Credentials operatorCredentials=null;
	
	private Credentials getOperatorCredentials() {
		if (operatorCredentials!=null) return operatorCredentials;
		AString operatorAddress=getOperatorAddress();
		if (operatorAddress==null) return null;
		
		List<Credentials> wallets=getLoadedWallets();
		for (Credentials c: wallets) {
			AString a=parseAddress(c.getAddress());
			if (operatorAddress.equals(a)) {
				operatorCredentials=c;
				return c;
			}
		}
		
		return null;
	}


	@Override
	public AString getOperatorAddress() {
		if (operatorAddress == null) {
			log.warn("operatorAddress is null in getOperatorAddress()");
			throw new IllegalStateException("operatorAddress is not set for this adapter ("+getAlias()+")");
		}
		return operatorAddress;
	}
	
	@Override
	public AString getDescription() {
		AString desc=super.getDescription();
		if (desc==null) return Strings.create("Undescribed Ethereum Network");
		return desc;
	}
	
	@Override
	public boolean validateSignature(String userKey, ABlob signature, ABlob message) {
		try {
			Blob pk = Blob.parse(userKey);
			
			Sign.SignatureData signatureData = getSigData(signature.getBytes());
			String recoveredAddress = recoverAddress(message, signatureData);
			return recoveredAddress.equalsIgnoreCase(pk.toHexString());
		} catch (SignatureException e) {
			return false;
		}
	}
	
	@Override
	public boolean verifyPersonalSignature(String message, String signature, String address) {
		try {		
			Blob pk = Blob.parse(address);
			if (pk == null) throw new IllegalArgumentException("Invalid EVM address: " + address);
			if (pk.count() != 20) throw new IllegalArgumentException("Invalid EVM address length: " + pk.count());
	
			Blob sigData = Blob.parse(signature);
			if (sigData == null) throw new IllegalArgumentException("Invalid signature data: " + signature);
	
			AString msg = Strings.create(message);
			if (msg == null) throw new IllegalArgumentException("Invalid message: " + msg);
			
			Sign.SignatureData signatureData = getSigData(sigData.getBytes());
			String recoveredAddress = recoverAddress(msg.toBlob(), signatureData);
			
			return recoveredAddress.equalsIgnoreCase(pk.toHexString());
		} catch (SignatureException e) {
			return false;
		}
	}

	private String recoverAddress(ABlob msg, Sign.SignatureData signatureData) throws SignatureException {
		Hash hash=Hashing.keccak256(msg.getBytes());
		BigInteger publicKey;
		publicKey = Sign.signedPrefixedMessageToKey(hash.getBytes(), signatureData);

		String recoveredAddress = Keys.getAddress(publicKey);
		return recoveredAddress;
	}
	
	Sign.SignatureData getSigData(byte[] signatureBytes) {
		byte[] r = new byte[32];
		byte[] s = new byte[32];
		System.arraycopy(signatureBytes, 0, r, 0, 32); // First 32 bytes
		System.arraycopy(signatureBytes, 32, s, 0, 32); // Next 32 bytes
		byte v = signatureBytes[64]; // Last byte (recovery id)
		
		if (v < 27) {
            v += 27;
        }
		
		return new Sign.SignatureData(v, r, s);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public AInteger checkTransaction(String expectedAdress,String tokenID,Blob tx) throws IOException {
		AString addr=parseAddress(expectedAdress);
		AString erc20Contract=parseERC20TokenID(tokenID);
		
		String txS="0x"+tx.toHexString(); // 0x needed in transaction hash for RPC
		TransactionReceipt receipt = getWeb3().ethGetTransactionReceipt(txS).send().getTransactionReceipt().orElse(null);
		// String status=receipt.getStatus();
		// if (status.equals("0x1")) return true;
		AString from = parseAddress(receipt.getFrom());
		if (!addr.equals(from)) throw new IllegalArgumentException("Expected address "+addr+" but transaction was from "+from);
		if (!receipt.isStatusOK()) {
            return null;
        }
		// System.out.println(receipt);
		
		AInteger received=CVMLong.ZERO;
		for (org.web3j.protocol.core.methods.response.Log log : receipt.getLogs()) {
            // System.out.println(log);
            if (!log.getAddress().toLowerCase().equals("0x"+erc20Contract.toString())) {
                continue;
            }

            // Check if the log corresponds to the Transfer event
            if (log.getTopics().get(0).equals(TRANSFER_SIGNATURE)) {
                // Decode the indexed parameters (from, to)
            	ArrayList<String> topics=new ArrayList<>(log.getTopics());
            	ArrayList<Type> indexedTopics=new ArrayList<Type>();
            	ArrayList<TypeReference<Type>> params=new ArrayList<>(TRANSFER_EVENT.getIndexedParameters());
            	// note the first topic is the event hash, so we can skip it
            	for (int i=0; i<params.size(); i++) {
            		Type type=FunctionReturnDecoder.decodeIndexedValue(topics.get(i+1), params.get(i));
            		indexedTopics.add(type);
            	}
            	
                String transferFrom = indexedTopics.get(0).getValue().toString().toLowerCase();
                String transferTo = indexedTopics.get(1).getValue().toString().toLowerCase();

                // Decode the non-indexed parameter (value)
                List<Type> nonIndexedValues = FunctionReturnDecoder.decode(
                        log.getData(), TRANSFER_EVENT.getNonIndexedParameters());
                String value = nonIndexedValues.get(0).getValue().toString();

                // Validate the Transfer event
                AString recAddress=parseAddress(transferTo);
                AString expectedReceiver=getReceiverAddress();
                if (!recAddress.equals(expectedReceiver)) {
                	throw new IllegalArgumentException("TX Transfer to "+recAddress+" did not equal expected receiver address "+expectedReceiver);
                }
                
                if (!addr.equals(parseAddress(transferFrom))) {
                	throw new IllegalArgumentException("TX Transfer from "+transferFrom+" did not match user address "+addr);
                }
                
                AInteger rec=AInteger.parse(value);
                if (!rec.isNatural()) throw new IllegalStateException("Negative transfer found in transaction");
                
                // All OK so credit receipt value    
                received=received.add(rec);
            }
        }
		
		return received;
	}

	@Override
	public AString getReceiverAddress() {
		if (receiverAddress==null) {
			receiverAddress =parseAddress(RT.getIn(config,Fields.RECEIVER_ADDRESS));
		}
		return receiverAddress;
	}

	@Override
	public AString parseUserKey(String address) throws IllegalArgumentException {
		AString parsed=parseAddress(address);
		if (parsed!=null) return parsed; // acceptable address
		
		throw new UnsupportedOperationException("Can't parse user key like: "+address);
	}

	/**
	 * Loads wallets from the directory specified in the config
	 * @throws IOException If there's an error reading the directory or files
	 * @throws CipherException If there's an error decrypting the wallet files
	 */
	private void loadWalletsFromConfig() throws IOException, CipherException {
		// Get the key directory from config
		AString keyDir = RT.getIn(engine.getConfig(), Fields.OPERATIONS, Fields.KEY_DIR);
		if (keyDir == null) {
			log.warn("No key-dir specified in config, skipping wallet loading");
			return;
		}
		
		
		// Create the EVM wallets directory path
		String evmWalletsPath = keyDir.toString() + "/.evm-wallets";
		File evmWalletsDir = FileUtils.getFile(evmWalletsPath);
		
		// Create directory if it doesn't exist
		if (!evmWalletsDir.exists()) {
			evmWalletsDir.mkdirs();
			log.info("Created EVM wallets directory: " + evmWalletsDir.getPath());
			return;
		}
		
		// Load wallets with default password (you might want to make this configurable)
		String password = "default-password"; // TODO: Make this configurable
		HashMap<String, Credentials> walletMap = loadWalletsFromDirectory(evmWalletsDir, password);
		loadedWallets = new ArrayList<>(walletMap.values());
		
		log.info("Loaded " + loadedWallets.size() + " wallets from " + evmWalletsDir.getPath());
	}

	/**
	 * Loads all wallet files from a directory
	 * @param directory The directory containing wallet files
	 * @param password The password to decrypt the wallet files
	 * @return HashMap of address (20-byte hex string, no 0x) to Credentials
	 * @throws IOException If there's an error reading the directory or files
	 * @throws CipherException If there's an error decrypting the wallet files
	 */
	public java.util.HashMap<String, Credentials> loadWalletsFromDirectory(File directory, String password) throws IOException, CipherException {
		java.util.HashMap<String, Credentials> credentialsMap = new java.util.HashMap<>();
		
		if (!directory.exists() || !directory.isDirectory()) {
			throw new IllegalArgumentException("Directory does not exist or is not a directory: " + directory.getPath());
		}
		
		File[] walletFiles = directory.listFiles((dir, name) -> name.endsWith(".json"));
		if (walletFiles == null) {
			log.warn("No wallet files found in directory: " + directory.getPath());
			return credentialsMap;
		}
		
		for (File walletFile : walletFiles) {
			try {
				Credentials cred = WalletUtils.loadCredentials(password, walletFile);
				String address = cred.getAddress();
				if (address.startsWith("0x") || address.startsWith("0X")) address = address.substring(2);
				address = address.toLowerCase();
				credentialsMap.put(address, cred);
				log.info("Loaded wallet: " + address + " from " + walletFile.getName());
			} catch (Exception e) {
				log.warn("Failed to load wallet from " + walletFile.getName() + ": " + e.getMessage());
			}
		}
		
		return credentialsMap;
	}
	
	/**
	 * Get the loaded wallets
	 * @return List of loaded credentials
	 */
	public List<Credentials> getLoadedWallets() {
		return new ArrayList<>(loadedWallets);
	}

	@Override
	public Blob parseTransactionID(AString tx) {
		Blob b=Blob.parse(tx.toString());
		if (b.count()!=32) return null;
		return b;
	}


	/**
	 * @return the web3
	 */
	public Web3j getWeb3() {
		return web3;
	}









}


	
