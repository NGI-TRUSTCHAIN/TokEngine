package tokengine.adapter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.lang.RT;
import convex.core.util.Utils;
import tokengine.Engine;
import tokengine.Fields;

public abstract class AAdapter<AddressType extends ACell> {

	protected static final Logger log = LoggerFactory.getLogger(AAdapter.class.getName());
	
	/** The config map for this adapter */
	protected final AMap<AString,ACell> config;
	
	/** 
	 * The tokens map for this adapter 
	 * CAIP Asset ID -> Token record
	 * 
	 * Where:
	 * - Key = CAIP asset ID like "slip44:840" or "erc20:0x0123456789012345678901234567890123456789"
	 * - Value = Token Record provided from config and transformed by addTokenMapping
	 */
	protected Index<AString,AMap<AString,ACell>> tokens=Index.none();
	
	/** 
	 * The aliases this adapter: Alias -> CAIP Asset ID 
	 * 
	 * Where:
	 * - Key = Alias e.g. "USDC"
	 * - Value = CAIP Asset ID e.g. "cad29:132"
	 */
	protected Index<AString,AString> tokenAliases=Index.none();

	/** The alias for this adapter */
	protected final AString alias;

	protected final Engine engine;
	
	protected AAdapter(Engine engine,AMap<AString, ACell> config) {
		this.engine=engine;
		this.config=config;
		this.alias=RT.ensureString(RT.getIn(config, Fields.ALIAS));
	}

	public abstract void start() throws Exception;
	
	public abstract void close();

	/** Get the canonical Chain ID of this adapter, in CAIP-2 format */
	public AString getChainID() {
		return RT.getIn(config, Fields.CHAIN_ID);
	}
	
	/** Get the CAIP-2 chain ID as a Java String */
	protected String getChainIDString() {
		return getChainID().toString();
	}
	

	/**
	 * Get the Config map for this handler
	 * @return Config map as JSON structure
	 */
	public AMap<AString, ACell> getConfig() {
		return config;
	}
	
	/**
	 * Get the alias for this adapter
	 * @return The alias as AString
	 */
	public AString getAliasField() {
		return alias;
	}
	
	@Override
	public String toString() {
		return this.getClass().getSimpleName()+" chainID="+getChainID()+" alias="+alias;
				
	}

	/**
	 * Gets the balance of the given address account as an Integer
	 * @return Balance as an integer, or null if the target address does not exist
	 */
	public abstract AInteger getBalance(String asset,String address) throws IOException;

	/**
	 * Gets the balance of the current operator as an Integer
	 * @param tokenKey CAIP-19 ID of asset e.g. "cad29:132"
	 * @return Balance of the operator
	 */
	public abstract AInteger getOperatorBalance(AString tokenKey) throws IOException;

	
	/**
	 * Parses a CAIP-10 address for this adapter.
	 * @param caip10 CAIP-10 account_address (Assumes chain ID removed)
	 * @return AddressType representing an Address for this adapter, or null if argument is null
	 * @throws IllegalArgumentException If account address format is invalid
	 */
	public abstract AddressType parseAddress(String caip10) throws IllegalArgumentException;
	
	/**
	 * Parses an object into an AddressType for this adapter.
	 * Accepts AddressType, AString, or String. Normalizes as needed.
	 * @param obj The object to parse
	 * @return AddressType in normalised form
	 * @throws IllegalArgumentException if the object cannot be parsed
	 */
	public abstract AddressType parseAddress(Object obj) throws IllegalArgumentException;

	/**
	 * Gets the canonical userKey for a given account address
	 * 
	 * @param caip10 CAIP-10 account_address (Assumes chain ID removed)
	 * @return String representing an Address for this adapter
	 * @throws IllegalArgumentException If account address format is invalid
	 */
	public abstract AString parseUserKey(String caip10) throws IllegalArgumentException;


	/**
	 * Perfom a payout on the given DLT
	 * @param token
	 * @param quantity
	 * @param destAccount
	 * @return String identifying the payout transaction. This should be a hex string.
	 * @throws Exception If the payout fails for any reason
	 */
	public abstract AString payout(String token, AInteger quantity, String destAccount) throws Exception;
	
	/**
	 * 
	 * @param message The message as a plain text string
	 * @param signature Signature in hex
	 * @param account Public key / account in hex
	 * @return True if signature verified, false otherwise
	 */
	public abstract boolean verifyPersonalSignature(String message, String signature, String account);

	/**
	 * Get the alias for this adapter
	 * @return
	 */
	public String getAlias() {
		AMap<AString,ACell> config=getConfig();

		return Utils.toString(config.get(Fields.ALIAS));
	}
	
	/**
	 * Get the alias for a token on this adapter
	 * @param destToken Token identifier for this adapter (alias or CAIP-19 token ID)
	 * @return Canonical token alias
	 */
	public AString getTokenAlias(String token) {
		AString tokenKey=engine.getTokenKey(this, token);
		return RT.getIn(tokens, tokenKey,Fields.ALIAS);
	}
	
	public AString getDescription() {
		AMap<AString,ACell> config=getConfig();
		AString desc=RT.ensureString(RT.getIn(config,Fields.DESCRIPTION));
		return desc;
	}

	/**
	 * Gets the operator address as an adapter-defined type as returned from parseAddress
	 * @return AddressType instance
	 */
	public abstract AddressType getOperatorAddress();

	/**
	 * Check a transaction for valid receipt of a token
	 * @param address Address of sender of funds
	 * @param caipTokenID Token ID in CAIP-19 tokenID format
	 * @param tx Transaction ID
	 * @return amount received from the Token transfer, or null if not a valid transaction
	 * @throws IOException in case of DLT connection failure
	 */
	public abstract AInteger checkTransaction(String address, String caipTokenID, Blob tx) throws IOException;

	/**
	 * Parse a transaction ID, returning a canonical Blob. This should be unique for any distinct valid transaction
	 * on any given DLT
	 * @param tx Transaction ID as a string
	 * @return Blob containing transaction ID, or null if not valid
	 */
	public abstract Blob parseTransactionID(AString tx);

	/**
	 * Gets the address of the TokEngine receiver account, in the adapter AddressType format
	 * 
	 * @return Receiver address, or null if not defined
	 */
	public abstract AddressType getReceiverAddress();

	/**
	 * Parse a CAIP-19 asset ID into the canonical form for this adapter
	 * @param assetID CAIP-19 asset ID (may omit chain ID, e.g. "cad29:567" )
	 * @return Asset identifier specific for this chain e.g. [#567 :foo] 
	 */
	public abstract ACell parseAssetID(String assetID);
	

	
	/**
	 * Convert a chain-specific asset identifier to a CAIP-19 asset ID
	 * @param adapterAssetID Chain-specific asset identifier e.g. the Vector [#567 3]
	 * @return CAIP19 asset ID e.g. cad29:567-3
	 */
	public abstract AString toCAIPAssetID(ACell adapterAssetID);

	/**
	 * Adds a token mapping for this network
	 * @param tokenAlias Cross-chain alias name e.g. "CVM". Must be precise.
	 * @param assetID CAIP-19 asset ID (may omit chain ID). May be "cad29:test" to deploy a new fungible token
	 * @param tnet Mapping information record as per config file.
	 * @throws Exception 
	 */
	public void addTokenMapping(AString tokenAlias, AString assetID, AMap<AString, ACell> tnet) throws Exception {
		// Strip leading chainID as per CAIP-19 if needed
		AString chainID=getChainID();
		if (assetID.startsWith(chainID)&&assetID.charAt(chainID.count())=='/') {
			assetID=assetID.slice(chainID.count()+1);	
		}
		
		ACell asset;
		if (assetID.startsWith("cad29:test")) {
			// TODO: other adapter test tokens?
			asset=deployTestAsset(tnet);
			assetID=toCAIPAssetID(asset);
			if (assetID==null) throw new IllegalArgumentException("Unable to recognise test asset ID: "+asset);
		} else {
			// we should be able to parse as an adapter-specific asset ID
			asset=parseAssetID(assetID.toString().toLowerCase());
			assetID=toCAIPAssetID(asset);
			if (asset==null) {
				throw new IllegalArgumentException("Unable to parse asset ID "+asset+" for DLT "+getChainID());
			}
		}
		
		AMap<AString, ACell> trec=tnet;
		
		if (tokens.containsKey(assetID)) throw new IllegalStateException("Trying to add duplicate asset: "+assetID);
		
		// Ensure alias is present and correct
		trec=trec.assoc(Fields.ALIAS, tokenAlias);
		
		// Add token mapping
		tokens=tokens.assoc(assetID, trec);
		tokenAliases=tokenAliases.assoc(tokenAlias, assetID);
		
		if (tokens==null) {
			throw new Exception("Problem setting token mapping? "+assetID+" = "+trec);
		}

		log.info("Added asset "+tnet.get(Fields.SYMBOL)+" on network "+getChainID()+" with Asset ID "+assetID);
	}

	/**
	 * Deploy a test asset for this adapter
	 * @param tnet Token config for test asset
	 * @return Asset ID
	 */
	protected ACell deployTestAsset(AMap<AString, ACell> tnet) {
		throw new UnsupportedOperationException("Cannot deploy test assets for "+this.getClass());
	}

	/**
	 * Looks up a canonical CAIP-19 asset ID (e.g. "cad29:72") for the identified token on this adapter
	 * 
	 * @param token Token identifier, may be an alias, symbol or CAIP-19 asset ID
	 * @return CAIP-19 Asset ID or null if token is not defined in this adapter
	 */
	public AString lookupCAIPAssetID(String token) {
		if (tokens==null) return null;
		try {
			AString id=toCAIPAssetID(parseAssetID(token)); // parse and canonicalise
			if (tokens.containsKey(id)) return id; // valid CAIP-19 ID, exact match
		} catch (IllegalArgumentException e) {
			// fall through to check alias / symbol
		}
		
		AString exactToken=Strings.create(token);
		
		long n=tokens.count();
		for (int i=0; i<n; i++) {
			MapEntry<AString, AMap<AString, ACell>> me = tokens.entryAt(i);
			AMap<AString, ACell> mapping=me.getValue();
			if (exactToken.equals(RT.getIn(mapping, Fields.ALIAS))) {
				return me.getKey();
			}
			if (exactToken.equals(RT.getIn(mapping, Fields.SYMBOL))) {
				return me.getKey();
			}
		}
		return null;
	}

	public abstract boolean validateSignature(String userKey, ABlob signature, ABlob message);

	/**
	 * Get the token index for this adapter (CAIP Asset ID -> Config)
	 * @return Token index
	 */
	public Index<AString,AMap<AString,ACell>> getTokens() {
		return tokens;
	}










}
