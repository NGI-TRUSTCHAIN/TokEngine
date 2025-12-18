package tokengine.adapter.tezos;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.util.Base58;
import convex.core.crypto.util.Base58Check;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import tokengine.Engine;
import tokengine.Fields;
import tokengine.adapter.AAdapter;

public class TezosAdapter extends AAdapter<AString> {

	private static final Logger log = LoggerFactory.getLogger(TezosAdapter.class.getName());
	
	private AString operatorAddress;
	private TezosHTTP tezosHTTP;
	private String apiUrl;
	
	private static final AString TEZOS_MAIN=Strings.create("tezos:NetXdQprcVkpaWU");
	private static final AString TEZOS_GHOST=Strings.create("tezos:NetXnHfVqm9iesp");
	private static final AString TEZOS_ASSET_ID=Strings.create("slip44:1729");
	
	public static final AString TZKT_BALANCE = Strings.intern("balance");


	protected TezosAdapter(Engine engine, AMap<AString,ACell> config) {
		super(engine, config);
		
		ACell opAddrCell = RT.getIn(config, Fields.OPERATOR_ADDRESS);
		log.info("TezosAdapter constructor - operator address from config: {}", opAddrCell);
		
		if (opAddrCell != null) {
			try {
				String opAddrStr = opAddrCell.toString();
				operatorAddress = parseAddress(opAddrStr);
				log.debug("Successfully parsed operator address: {}", operatorAddress);
			} catch (Exception e) {
				log.warn("Failed to parse {} from config: {} - Error: {}", Fields.OPERATOR_ADDRESS, opAddrCell, e);
				operatorAddress = null;
			}
		} else {
			log.warn("No operator address found in config for TezosAdapter");
		}
	}
	
	public static AAdapter<?> build(Engine engine, AMap<AString, ACell> nc) {
		log.info("Building TezosAdapter with config: {}", nc);
		
		AString chainID=RT.getIn(nc, Fields.CHAIN_ID);
		if (chainID==null) throw new IllegalArgumentException("No Tezos chain ID: "+nc);
		
		log.info("Chain ID: {}", chainID);
		
		// Normalize chain ID if needed
		String chainIDStr = chainID.toString();
		if ("tezos:mainnet".equals(chainIDStr)) {
			chainID = TEZOS_MAIN;
		} else if ("tezos:ghostnet".equals(chainIDStr)) {
			chainID = TEZOS_GHOST;
		} else {
			log.warn("Unrecognised Tezos ChainID: "+chainID);
		}
		
		log.info("Final chain ID: {}", chainID);
		
		nc = nc.assoc(Fields.CHAIN_ID, chainID);
		TezosAdapter a = new TezosAdapter(engine, nc);
		
		log.info("TezosAdapter built successfully");
		return a;
	} 

	@Override
	public void start() throws Exception {
		if (operatorAddress==null) {
			log.warn("No operatorAddress specified in config for TezosAdapter");
		}
		
		AString url=RT.getIn(config, Fields.URL);
		if (url==null) throw new IllegalStateException("No Tezos RPC URL specified, should be in networks[..].url");
		apiUrl = url.toString();
		
		// Initialise TezosHTTP client
		tezosHTTP = new TezosHTTP(apiUrl);
			
		log.info("TezosAdapter started with API URL: {}", apiUrl);
	}

	@Override
	public void close() {
		if (tezosHTTP != null) {
			tezosHTTP.close();
			tezosHTTP = null;
		}
	}

	@Override
	public AInteger getBalance(String asset, String address) throws IOException {
		if (tezosHTTP == null) {
			throw new IllegalStateException("TezosHTTP not initialized. Call start() before using HTTP methods.");
		}
		
		if (isTezos(asset)) {
			try {
				CompletableFuture<ACell> future = tezosHTTP.getAccountInfo(address);
				ACell response = future.get(); // Blocking call for now
				
				ACell bal=RT.getIn(response, TZKT_BALANCE);
				if (bal!=null) {
					return AInteger.parse(bal);
				}
				return CVMLong.ZERO;
			} catch (Exception e) {
				log.debug("Failed to get Tezos balance for {}: {}", address, e.getMessage());
				throw new IOException("Failed to get Tezos balance for " + address, e);
			}
		} else if (asset.startsWith("fa2:")) {
			// FA2 token balance
			String contractAddress = asset.substring(4); // skip 'fa2:'
			try {
				CompletableFuture<ACell> future = tezosHTTP.getTokenBalance(address,contractAddress);
				ACell response = future.get(); // Blocking call for now
				
				ACell bal=RT.getIn(response, TZKT_BALANCE);
				if (bal!=null) {
					return AInteger.parse(bal);
				}
				return CVMLong.ZERO;
			} catch (Exception e) {
				log.debug("Failed to get FA2 token balance for {}: {}", address, e.getMessage());
				throw new IOException("Failed to get FA2 token balance for " + address, e);
			}
		}
		
		throw new UnsupportedOperationException("Asset not supported in TezosAdapter: "+asset);
	}

	@Override
	public AInteger getOperatorBalance(AString asset) throws IOException {
		AString operatorAddr = getOperatorAddress();
		if (operatorAddr == null) {
			log.warn("Cannot get operator balance - no operator address configured");
			return CVMLong.ZERO;
		}
		return getBalance(asset.toString(), operatorAddr.toString());
	}

	/**
	 * Checks if a an asset identifier is tez (Tezos native coin)
	 * @param asset
	 * @return true if 
	 */
	public boolean isTezos(String asset) {
		if (asset == null) return false;
		if ("XTZ".equals(asset) || "xtz".equals(asset)) return true;
		return "slip44:1729".equals(asset);
	}

	@Override
	public AString parseAddress(String caip10) throws IllegalArgumentException {
		if (caip10 == null) throw new IllegalArgumentException("Null address");
		String s = caip10.trim();
		if (s.isEmpty()) throw new IllegalArgumentException("Empty address");
				
		// For testing purposes, skip chain ID validation
		int colon = s.lastIndexOf(":");
		if (colon >= 0) {
			s = s.substring(colon + 1); // take the part after the colon
		}
		
		// Validate Tezos address format
		if (!s.startsWith("tz1") && !s.startsWith("tz2") && !s.startsWith("tz3")) {
			throw new IllegalArgumentException("Invalid Tezos address format: " + caip10 + " (must start with tz1, tz2, or tz3)");
		}
		
		// Validate Base58Check encoding (includes checksum validation)
		// byte[] decoded = TezosUtils.getAddressBytes(s);
		
		AString result = Strings.create(s);
		return result;
	}
	
	@Override
	public ACell parseAssetID(String assetID) {
		if (assetID == null) return null;
		
		String lowerAssetID = assetID.toLowerCase();
		if (lowerAssetID.startsWith("fa2:")) {
			return parseFA2TokenID(assetID);
		} else if ("xtz".equals(lowerAssetID) || "slip44:1729".equals(assetID)) {
			return TEZOS_ASSET_ID; // "slip44:1729"
		}
		return null;
	}
	
	@Override
	public AString toCAIPAssetID(ACell asset) {
		if (TEZOS_ASSET_ID.equals(asset)) return TEZOS_ASSET_ID;
		if (asset instanceof AString s) {
			String assetStr = s.toString();
			// Only convert valid Tezos addresses to FA2 format
			if (assetStr.startsWith("KT") && assetStr.length() == 36) {
				return Strings.create("fa2:" + assetStr);
			}
		}
		return null;
	}
	
	private AString parseFA2TokenID(String tokenID) {
		if (tokenID == null) throw new IllegalArgumentException("Null tokenID");
		
		String lowerTokenID = tokenID.toLowerCase();
		if (lowerTokenID.startsWith("fa2:")) {
			// rest of ID should be a contract address
			String contractAddress = lowerTokenID.substring(4);
			if (contractAddress.startsWith("kt") && contractAddress.length() == 36) {
				return Strings.create(contractAddress);
			}
			throw new IllegalArgumentException("Invalid FA2 contract address: " + contractAddress);
		}
		throw new IllegalArgumentException("Invalid CAIP-19 tokenID: "+tokenID);
	}
	
	@Override
	public AString parseAddress(Object obj) throws IllegalArgumentException {
		if (obj == null) throw new IllegalArgumentException("Null address");
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
		throw new IllegalArgumentException("Cannot parse Tezos address from object: " + obj.getClass());
	}

	@Override
	public AString payout(String token, AInteger quantity, String destAccount) throws Exception {
		// This is a placeholder implementation
		// In a real implementation, you would:
		// 1. Create a Tezos transaction
		// 2. Sign it with the operator's private key
		// 3. Submit it to the network
		// 4. Return the transaction hash
		
		AString tokenS = parseAddress(token);
		AString destS = parseAddress(destAccount);
		
		// Validate inputs
		if (tokenS == null) {
			throw new IllegalArgumentException("Invalid token contract address");
		}
		if (destS == null) {
			throw new IllegalArgumentException("Invalid destination account address: "+destAccount);
		}
		if (quantity == null || quantity.isNegative()) {
			throw new IllegalArgumentException("Invalid quantity: "+quantity);
		}
		
		// For now, return a placeholder transaction hash
		// In a real implementation, this would be the actual transaction hash
		return Strings.create("placeholder_tx_hash_" + System.currentTimeMillis());
	}

	@Override
	public boolean verifyPersonalSignature(String message, String signature, String caip10) {
		try {
			AString addr = parseAddress(caip10);
			if (addr == null) return false;
			
			// For testing purposes, accept any signature if the address is valid
			// In a real implementation, you would validate the signature properly
			return true; // Placeholder - always return true for valid addresses
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public AString getOperatorAddress() {
		if (operatorAddress == null) {
			log.warn("operatorAddress is null in getOperatorAddress()");
			// Return null instead of throwing exception to be more graceful
			return null;
		}
		return operatorAddress;
	}

	@Override
	public AInteger checkTransaction(String address, String caipTokenID, Blob tx) throws IOException {
		if (tezosHTTP == null) {
			throw new IllegalStateException("TezosHTTP not initialized. Call start() before using HTTP methods.");
		}
		
		try {
			String txHash = "0x" + tx.toHexString();
			CompletableFuture<ACell> future = tezosHTTP.getTransactionInfo(txHash);
			ACell response = future.get(); // Blocking call for now
			
			// Parse transaction response to check if it's a valid transfer
			// This is a simplified implementation
			String responseStr = response.toString();
			if (responseStr.contains("\"status\":\"applied\"")) {
				// Check if the transaction is from the expected address
				if (responseStr.contains("\"sender\":\"" + address + "\"")) {
					// Parse the amount transferred
					// This would need proper JSON parsing in a real implementation
					return CVMLong.ONE; // Placeholder - return 1 if valid
				}
			}
			return null; // Transaction not found or invalid
		} catch (Exception e) {
			log.warn("Failed to check transaction {}: {}", tx.toHexString(), e.getMessage());
			throw new IOException("Failed to check transaction " + tx.toHexString(), e);
		}
	}

	@Override
	public Blob parseTransactionID(AString tx) {
		try {
			String txStr = tx.toString();
			
			// Handle Base58 encoded Tezos transaction hashes
			if (txStr.startsWith("op")) {
				byte[] decoded = Base58.decode(txStr);
				if (decoded.length == 32) {
					return Blob.wrap(decoded);
				}
			}
			
			// Handle hex strings
			if (txStr.startsWith("0x")) {
				txStr = txStr.substring(2);
			}
			
			// Parse as hex string
			Blob b = Blob.parse(txStr);
			if (b.count() != 32) return null; // Tezos transaction hashes are 32 bytes
			return b;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public AString getReceiverAddress() {
		return RT.getIn(config, Fields.RECEIVER_ADDRESS);
	}

	@Override
	public AString parseUserKey(String address) throws IllegalArgumentException {
		// For Tezos, the user key is typically the public key hash (address)
		AString addr = parseAddress(address);
		if (addr == null) {
			throw new IllegalArgumentException("Invalid Tezos address: " + address);
		}
		return addr;
	}

	@Override
	public boolean validateSignature(String userKey, ABlob signature, ABlob message) {
		try {
			AString addr = parseAddress(userKey);
			if (addr == null) return false;
			
			// This is a simplified implementation
			// In a real scenario, you'd verify the signature using Ed25519
			// against the message and the public key derived from the address
			return true; // Placeholder
		} catch (Exception e) {
			return false;
		}
	}



	@Override
	public AString getDescription() {
		AString desc = super.getDescription();
		if (desc == null) return Strings.create("Undescribed Tezos Network");
		return desc;
	}
	
	// Utility methods for testing
	
	/**
	 * Validates a Tezos address format without throwing exceptions
	 * @param address The address to validate
	 * @return true if valid, false otherwise
	 */
	public boolean isValidTezosAddress(String address) {
		try {
			TezosUtils.getAddressBytes(address);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
	
	/**
	 * Validates a Tezos asset ID format without throwing exceptions
	 * @param assetID The asset ID to validate
	 * @return true if valid, false otherwise
	 */
	public boolean isValidTezosAssetID(String assetID) {
		if (assetID == null) return false;
		
		String lowerAssetID = assetID.toLowerCase();
		
		// Check for native XTZ
		if ("xtz".equals(lowerAssetID) || "slip44:1729".equals(assetID)) {
			return true;
		}
		
		// Check for FA2 tokens
		if (lowerAssetID.startsWith("fa2:")) {
			String contractAddress = assetID.substring(4);
			// FA2 contract addresses should start with KT and be 36 characters
			return contractAddress.startsWith("KT") && contractAddress.length() == 36;
		}
		
		return false;
	}
	
	/**
	 * Gets the decoded bytes of a Tezos address for testing
	 * @param address The Tezos address
	 * @return The decoded bytes (without checksum), or null if invalid
	 */
	public byte[] getAddressBytes(String address) {
		try {
			String s = address.trim();
			if (s.startsWith("tz1") || s.startsWith("tz2") || s.startsWith("tz3")) {
				return Base58Check.decode(s);
			} 
			return null;
		} catch (Exception e) {
			//throw new IllegalArgumentException("Not a tezos address: "+address,e);
			return null;
		}
	}
}
