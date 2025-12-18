package tokengine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.util.FileUtils;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.etch.EtchStore;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.Cursors;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import tokengine.adapter.AAdapter;
import tokengine.adapter.convex.CVMAdapter;
import tokengine.adapter.evm.EVMAdapter;
import tokengine.adapter.kafka.Kafka;
import tokengine.adapter.tezos.TezosAdapter;
import tokengine.exception.PaymentException;

/**
 * Engine is the core application class for TokEngine
 * 
 * It implements transitions on the lattice data structure as follows:
 * :app
 *   "tokengine": app-specific key for tokengine state
 *     "credits": -> User Key -> Token Key (AString, e.g. "CVM") -> Credit balance (AInteger, >=0) 
 *     "receipts": -> Chain ID (AString e.g. "convex:protonet") -> TX ID (Blob, typically the transaction Hash) -> amount
 */
public class Engine {
	
	protected static final Logger log=LoggerFactory.getLogger("tokengine.Engine");
	
	/** Connection for the server operator */
	Convex convex;
	
	/** Embedded Peer server instance */
	Server server;
	
	/** etch store for TokEngine state */
	EtchStore etch=null;
	
	/** Kafka instance for audit logging */
	
	Kafka kafka;
	
	/** 
	 * Set to true for test mode. In test mode:
	 * - Some security assertions are relaxed
	 * - Some extra test features are enabled, e.g. auto-deploying test tokens
	 */
	final boolean testMode;
	
	final AMap<AString,ACell> config;	
	
	private boolean running=false;
	
	/**
	 * Root lattice cursor
	 */
	ACursor<ACell> latticeCursor;
	
	/**
	 * Tokens map of alias -> Token config
	 */
	AMap<AString,AMap<AString,ACell>> tokens=Maps.empty();
	
	/**
	 * stateCursor tracks the TokEngine state, stored in the TokEngine etch
	 */
	ACursor<AMap<AString,ACell>> stateCursor;
	
	/** Map of network aliases to adapters instance */
	protected final Map<AString,AAdapter<?>> adapters=new HashMap<>();
	
	public Engine(AMap<AString,ACell> config)  {
		this.config=config;
		this.testMode=RT.bool(RT.getIn(config,Fields.OPERATIONS, Fields.TEST));
		this.latticeCursor=Cursors.of(null);
		this.stateCursor=latticeCursor.path(Keywords.APP, Fields.TOKENGINE);
		
	}

	public synchronized void start() throws Exception {
		close(); // ensure everything empty / unconfigured
		startEtch(); // open Etch db and load lattice cursor
		startConvexPeer();	
		
		configureAdapters();
		loadTokens(); // load tokens and transfer maps once adapters are started
		
		startAdapters();	
		configureAuditService();
		
		running=true;
	}

	private void loadTokens() {
		AVector<AMap<AString,ACell>> configTokens=RT.ensureVector(config.get(Fields.TOKENS));
		if (configTokens==null) {
			log.warn("No 'tokens' configured in config: should be an array of token info maps. Tokengine will not allow any transfers.");
			return;
		}
		int n=configTokens.size();
		for (int i=0; i<n; i++) {
			AMap<AString,ACell> token=configTokens.get(i);
			AString alias=RT.ensureString(token.get(Fields.ALIAS));
			if (alias==null) throw new IllegalArgumentException("Token in config did not specify an alias: "+token);
			tokens=tokens.assoc(alias,token);
		}
		
		AMap<AString,AMap<AString,ACell>> configTransfers=RT.ensureMap(config.get(Fields.TRANSFERS));
		if (configTransfers==null) {
			log.warn("No '"+Fields.TRANSFERS+"' in config: should be an map of transfers. Tokengine will not allow any transfers.");
			return;
		}
		
		int tn=configTransfers.size();
		if (n!=tn) log.warn("Number of 'transfers' does not equal the number of 'tokens'. This is probably a mistake?");
		// Loop over  classes
		for (int i=0; i<n; i++) {
			MapEntry<AString, ?> transfer=configTransfers.get(i);
			AString tokenAlias=transfer.getKey();
			
			// Loop over configured equivalent token classes
			if (tokens.containsKey(tokenAlias)) {
				// Get the set of transfers for each token alias
				ADataStructure<ACell> tnets=RT.ensureDataStructure(transfer.getValue());
				
				// Loop over network mappings for this token
				int tcount=tnets.size();
				for (int j=0; j<tcount; j++) try {
					// Get token mapping in the transfers, might be a map entry or direct token mapping containing "network"
					AMap<AString, ACell> tnet = prepareTokenMapping(tnets.get(j));
					if (tnet==null) continue;

					AString netAlias = RT.ensureString(tnet.get(Fields.NETWORK));
					AString symbol=RT.ensureString(tnet.get(Fields.SYMBOL));
					if (symbol==null) {
						// Use the token alias as symbol by default
						symbol=RT.getIn(Fields.TOKENS,tokenAlias,Fields.SYMBOL);
						tnet=tnet.assoc(Fields.SYMBOL,symbol);
					}
					
					AString assetID=RT.ensureString(tnet.get(Fields.ASSET_ID));
					if (assetID==null) {
						log.warn("No assetID for token '"+tokenAlias+"', transfer mapping wil be ignored in "+tnet);
						continue;
					}
					AString chainID=lookupChainID(netAlias);
					if (chainID==null) {
						log.warn("Could not find network '"+netAlias+"' for token '"+tokenAlias+"', transfer mapping wil be ignored in "+tnet);
						continue;
					}
					tnet=tnet.assoc(Fields.CHAIN_ID, chainID);
					
					AAdapter<?> adapter=getAdapter(chainID);
					if (adapter==null) throw new Error("Adapter not found for chainID: "+chainID);

					ACell receiver=tnet.get(Fields.RECEIVER_ADDRESS);
					if (receiver==null) try {
						receiver=adapter.getReceiverAddress();
					} catch (Exception e) { /* still null */ };
					if (receiver==null) {
						tnet=tnet.assoc(Fields.RECEIVER_ADDRESS, adapter.getOperatorAddress());
						log.warn("No receiverAddress for token '"+tokenAlias+"' on network '"+netAlias+"'. Defaulting to operator address");
					}	
					
					// check assetID is valid
					adapter.addTokenMapping(tokenAlias,assetID,tnet);
				} catch (Exception e) {
					log.error("Error parsing token mapping",e);
					if (!isTest()) {
						throw new Error(e);
					}
				}
			} else {
				log.warn("Transfers configured for non-existent token alias '"+tokenAlias+"', these will be ignored.");
			}
		}

	}

	/**
	 * Checks if TokEngine is running in test configuration. 
	 * @return rue if in testing mode, false if in production
	 */
	public boolean isTest() {
		return testMode;
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> prepareTokenMapping(ACell e) {
		AMap<AString,ACell> tnet;
		if (e instanceof AMap m) {
			// we got a map, should contain "network" alias
			tnet=(AMap<AString,ACell>)m;
		} else {
			// we expect a netAlias, {mapping} pair
			MapEntry<AString,AMap<AString,ACell>> me=RT.ensureMapEntry(e);
			tnet=RT.ensureMap(me.getValue());
			tnet=tnet.assoc(Fields.NETWORK,me.getKey());
		}
		if (tnet==null) {
			log.warn("Value not a map in network mapping "+e+", entry ignored");
		}
		return tnet;
	}

	AString lookupChainID(AString networkAlias) {
		for (Map.Entry<AString, AAdapter<?>> me: adapters.entrySet()) {
			AAdapter<?> adapter=me.getValue();
			if (adapter.getAliasField().equals(networkAlias)) return adapter.getChainID();
			if (adapter.getChainID().equals(networkAlias)) return networkAlias;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private void startConvexPeer() throws IOException, LaunchException, InterruptedException, ConfigException {
		AKeyPair kp=isTest()?AKeyPair.createSeeded(6756):AKeyPair.generate();
		
		ACell convexConfig=RT.getIn(config, "convex");

		Map<Keyword, Object> peerConfig;
		if (convexConfig==null) {
			peerConfig=new HashMap<>();
			State genesis=Init.createState(List.of(kp.getAccountKey()));
			log.info("Creating test Convex network with genesis seed: "+kp.getSeed());
			peerConfig.put(Keywords.STATE,genesis);
			peerConfig.put(Keywords.KEYPAIR,kp);
		} else {
			peerConfig=(Map<Keyword, Object>) JSON.json(convexConfig);
			if (!peerConfig.containsKey(Keywords.KEYPAIR)) {
				if (isTest()) {
					log.info("No keypair provided, using test peer key with seed: "+kp.getSeed());
				} else {
					log.warn("No keypair provided, using generated peer key with public key: "+kp.getAccountKey());
				}
				peerConfig.put(Keywords.KEYPAIR, kp);
			}
		}
		
		server=API.launchPeer(peerConfig);
		
		convex=Convex.connect(server);
		convex.setAddress(Init.GENESIS_ADDRESS);
		convex.setKeyPair(kp);
	}

	private void configureAuditService() {
		AString kafkaLoc=RT.getIn(config, Fields.OPERATIONS, Fields.KAFKA);
		this.kafka=new Kafka(kafkaLoc);
	}

	private void configureAdapters() {
		// Set up adapters
		if (config==null) {
			log.warn("No config provided?");
		} else {
			@SuppressWarnings("unchecked")
			AVector<AMap<AString,ACell>> networks=(AVector<AMap<AString,ACell>>) RT.getIn(config, Fields.NETWORKS);
			if ((networks==null)||(networks.isEmpty())) {
				log.warn("No networks specified in config file.");
			} else for (AMap<AString,ACell> networkConfig: networks) try {
				AAdapter<?> a=buildAdapter(networkConfig);
				addAdapter(a);
				log.info("Configured adapter: "+a);
			} catch (Exception e) {
				log.warn("Failed to create adapter",e);
			}
		}
	}

	private void startEtch() throws IOException {
		// Etch file for Tokengine
		AString etchFile=RT.ensureString(RT.getIn(config, Fields.OPERATIONS,Fields.ETCH_FILE));
		if (etchFile==null) {
			etchFile=Strings.create("~/.tokengine/etch.db");
		} 
		if ("temp".equals(etchFile.toString())) {
			etch=EtchStore.createTemp();
			if (!testMode) {
				// WARN IF USING A TEMP FILE OUTSIDE TEST MODE
				log.warn("Temp Etch file created: "+etch.getFileName());
			}
		} else {
			etch=EtchStore.create(FileUtils.getFile(etchFile.toString()));
		}
		AMap<AString,ACell> loadedState=etch.getRootData();
		
		
		if (loadedState==null) {
			loadedState=Maps.of(Fields.CREDITS,Maps.empty(),Fields.CONFIG,config);
			log.info("Initialising new TokEngine state database with hash "+loadedState.getHash());
		} else {
			if (!config.equals(RT.getIn(loadedState, Fields.CONFIG))) {
				log.warn("TokEngine config has changed");
				loadedState=loadedState.assoc(Fields.CONFIG, config);
			}
			if (RT.getIn(loadedState, Fields.CREDITS)==null) throw new Error("Etch state appears to be incorrect for TokEngine in "+etch);
			log.info("Loaded TokEngine state database with hash "+loadedState.getHash());
		}
		this.latticeCursor.set(loadedState);
		persistState();
	}
	
	private AAdapter<?> buildAdapter(AMap<AString, ACell> nc) throws Exception {
		AString id=RT.ensureString(RT.getIn(nc, Fields.CHAIN_ID));
		if (id==null) throw new IllegalArgumentException("No chainID in network config: "+nc);
		String[] caip2=id.toString().split(":");
		String type=caip2[0];
		if ("convex".equals(type)) return CVMAdapter.build(this,nc);
		else if ("eip155".equals(type)) return EVMAdapter.build(this,nc);
		else if ("tezos".equals(type)) return TezosAdapter.build(this,nc);
		else throw new IllegalArgumentException("Unrecognised chain type: "+type);
	}

	public Convex getConvex() {
		return convex;
	}
	
	public ArrayList<AAdapter<?>> getAdapters() {
		return new ArrayList<>(adapters.values());
	}
	
	public ArrayList<Object> getAdapterConfigs() {
		ArrayList<Object> handlers=new ArrayList<>();
		for (Map.Entry<AString,AAdapter<?>> me: adapters.entrySet()) {
			AAdapter<?> adapter=me.getValue();
			handlers.add(adapter.getConfig());
		}
		return handlers;
	}
	
	private void startAdapters() {
		for (Map.Entry<AString,AAdapter<?>> me: adapters.entrySet()) {
			AAdapter<?> adapter=me.getValue();
			try {
				adapter.start();
				log.info("Started adapter: "+adapter);
			} catch (Exception e) {
				log.warn("Failed to start adapter: "+adapter,e);
			}
		}
	}

	public void addAdapter(AAdapter<?> adapter) {
		adapters.put(adapter.getChainID(),adapter);
	}
	
	/**
	 * Get adapter for a given chain ID or alias
	 * @param chainID or alias
	 * @return Adapter, or null if not defined
	 */
	public AAdapter<?> getAdapter(AString chainID) {
		AString id=chainID;
		AAdapter<?> ad= adapters.get(id);
		if (ad != null) return ad;
		
		// If not found by chain ID, try to find by alias
		for (AAdapter<?> adapter : adapters.values()) {
			AString alias=adapter.getAliasField();
			if (alias != null && id.equals(alias)) {
				return adapter;
			}
		}
		return null;
	}
	
	public AInteger getBalance(String acct, AString chainID, String token) throws IOException {
		AAdapter<?> ad=getAdapter(chainID);
		if (ad==null) throw new IllegalStateException("Chain ID not valid: "+chainID);
		return ad.getBalance(token, acct);
	}

	
	public synchronized void close() {
		try {
			if (etch!=null) {
				try {
					persistState();
				} catch (IOException e) {
					log.warn("Failed to persist Etch state",e);
				}
				etch.close();
			}
			etch=null;
			if (server!=null) server.close();
	
			closeAdapters();
	
			if (convex!=null) convex.close();
			
			// shut down audit logging last, just in case
			if (kafka!=null) kafka.close();
		} finally {
			running=false;
			server=null;
			convex=null;
			kafka=null;
		}
	}
	
	public void awaitClose() throws InterruptedException {
		while (running) {
			Thread.sleep(100);
		}
	}
	
	private void persistState() throws IOException {
		etch.setRootData(latticeCursor.get());
		etch.flush();
	}

	private void closeAdapters() {
		for (Map.Entry<AString,AAdapter<?>> me: adapters.entrySet()) {
			AAdapter<?> adapter=me.getValue();
			adapter.close();
		}
	}



	public AMap<AString,ACell> getConfig() {
		return config;
	} 

	public static Engine launch(AMap<AString, ACell> config) throws Exception {
		Engine engine=new Engine(config);
		engine.start();
		return engine;
	}

	public ACell getStatus() {
		AMap<AString,ACell> status=Maps.empty();
		status=status.assoc(Fields.ADAPTERS, Vectors.of(getAdapterConfigs().toArray()));
		if (server!=null) {
			status=status.assoc(Fields.LOCAL_CONVEX, Strings.create(server.getHostAddress().toString()));
		}
		return status;
	}

	/**
	 * Makes a deposit given a unique deposit proof
	 * @param adapter
	 * @param token
	 * @param address Address of user
	 * @param depositProof
	 * @return Integer amount deposited, or null if transaction could not be verified
	 * @throws IOException
	 */
	public AInteger makeDeposit(AAdapter<?> adapter, String token, String address, AMap<AString,ACell> depositProof) throws IOException, PaymentException {
		AString tokenKey=getTokenKey(adapter,token);
		if (tokenKey==null) {
			Set<AString> tokens=adapter.getTokens().keySet();
			throw new IllegalArgumentException("Token not supported on this DLT: "+token+" expected one of "+tokens);
		}
		
		// Check transaction is Valid: TODO: confirm fields
		AString tx=RT.ensureString(RT.getIn(depositProof, Fields.TX));
		Blob txID=adapter.parseTransactionID(tx);
		if (txID==null) throw new IllegalArgumentException("Unable to parse transaction ID: "+tx);
		AInteger received=adapter.checkTransaction(address,adapter.lookupCAIPAssetID(token).toString(),txID); 
		if (received==null) {
			return null; // null = failed to verify
		} 
			
		AString userKey=adapter.parseUserKey(address);
		if (userKey==null) throw new IllegalArgumentException("Invalid user account: "+address);

		if (received.isZero()) {
			log.warn("Deposit amount of zero by "+address);
		} else if (received.isNegative()) {
			String msg="Negative seposit quantity? From "+address+ " in "+depositProof;
			log.warn(msg);
			throw new PaymentException("Negative deposit quantity");
		}
		
		// We do this atomically, since it needs to update balances and log deposit
		stateCursor.updateAndGet(state->{
			AString chainID=adapter.getChainID();
			ACell existingTx=RT.getIn(state, Fields.RECEIPTS,chainID,txID);
			if (existingTx!=null) Utils.sneakyThrow(new PaymentException("Deposit already made for transaction "+txID));
			
			AInteger existingBalance=RT.getIn(state, Fields.CREDITS, userKey, tokenKey);
			if (existingBalance==null) {
				existingBalance=CVMLong.ZERO;
			}
			AInteger newBalance=existingBalance.add(received);
			state=RT.assocIn(state, newBalance, Fields.CREDITS, userKey, tokenKey);
			state=RT.assocIn(state, received, Fields.RECEIPTS, chainID,txID);
			return state;
		});
		
		AMap<AString,ACell> logVal=Maps.of(
				Fields.TYPE,"CREDIT",
				Fields.TX,txID.toString(),
				Fields.TS,getTimestampString(),
				Fields.AMOUNT,received.toString(),
				Fields.NETWORK,adapter.getChainID(),
				Fields.TOKEN,adapter.lookupCAIPAssetID(token),
				Fields.ACCOUNT,userKey);
		this.postAuditMessage(Engine.getRequest(),logVal);
		
		return received; // success case with positive deposit
	} 
	
	/**
	 * Handle payout of funds from the operator
	 */
	@SuppressWarnings("rawtypes")
	public AString makePayout(String target, String asset, AAdapter adapter, AInteger quantity, AMap<AString,ACell> depositProof)  {
		try {
			AString tokenKey=getTokenKey(adapter,asset);
			AInteger operatorBalance = adapter.getOperatorBalance(tokenKey);

			if (RT.lt(new ACell[] {operatorBalance,quantity}).booleanValue()) {
				log.warn("Attempted payout but insufficent operator balance available!");
				throw new IllegalStateException("Insuffient operator payout balance: "+operatorBalance);
			}
			
			AString r=adapter.payout(asset, quantity, target);

			AMap<AString,ACell> logVal=Maps.of(
					Fields.TYPE,"PAYOUT",
					Fields.TX,r,
					Fields.TS,getTimestampString(),
					Fields.AMOUNT,quantity.toString(),
					Fields.NETWORK,adapter.getChainID(),
					Fields.TOKEN,asset,
					Fields.ACCOUNT,target);
			this.postAuditMessage(Engine.getRequest(),logVal);
			
			return r;
		} catch (Exception e) {
			throw new IllegalStateException("Unable to process payout of "+asset,e);
		} 
	}
	
	/**
	 * Get the canonical token Key, as a CAIP-19 full asset type. This should be an index for virtual balances
	 * @param token Token identifier
	 * @return AString identifier for the token, or null if not available / defined
	 */
	public AString getTokenKey(AAdapter<?> adapter, String token) {
		AString assetID=adapter.lookupCAIPAssetID(token.trim());
		if (assetID==null) {
			log.debug("Couldn't find token: "+token+" on network "+adapter.getChainID());
			return null;
		}
		AString result=adapter.getChainID().append("/").append(assetID);
		return result;
	} 
	
	/**
	 * Get the canonical token Key, as a CAIP-19 full asset type. This should be an index for virtual balances
	 * @param network Net work to look up
	 * @param token Token identifier
	 * @return AString identifier for the token, or null if not available / defined
	 */
	public AString getTokenKey(AString network, String token) {
		AAdapter<?> adapter=getAdapter(network);
		if (adapter==null) throw new IllegalArgumentException("Could not find DLT adpater: "+network);
		return getTokenKey(adapter,token);
	} 

	/**
	 * Posts a message to the audit log.
	 * @param message
	 * @return True if queued for sending, false if Kafka not configured
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public boolean postAuditMessage(AString key,AMap message) {
		if (kafka==null) return false;
		return kafka.log(key,message);
	}
	
	/**
	 * Get the virtual credit for a given asset / user pair
	 * @param assetKey asset key in canonical form
	 * @param userKey user key in canonical form (i.e. parsed by adapter)
	 * @return Virtual balance, or null if the asset / user pair has no virtual balance
	 */
	public synchronized AInteger getVirtualCredit(AString assetKey, AString userKey) {
		AMap<AString,ACell> state=this.stateCursor.get();
		
		// Increment balance in "deposits"-> network ID -> asset ID -> User Key
		AInteger balance=RT.getIn(state, Fields.CREDITS,userKey,assetKey);
		return balance;
	}
	
	/**
	 * Add virtual credit into the TokEngine state
	 * @param tokenKey
	 * @param userKey
	 * @param amount
	 * @return Updated balance
	 */
	@SuppressWarnings("unchecked")
	public synchronized AInteger addVirtualCredit(AString tokenKey, AString userKey, AInteger amount) {
		if (amount.isNegative()) throw new IllegalArgumentException("Cannot add negative credit: "+amount);
		AInteger current=getVirtualCredit(tokenKey, userKey);
		if (current==null) current=CVMLong.ZERO;
		AInteger newBalance=current.add(amount);
		this.stateCursor.update(state->(AMap<AString,ACell>)RT.assocIn(state, newBalance, Fields.CREDITS,userKey,tokenKey));
		
		AMap<AString,?> msg=getBaseLogMessage("CREDIT");
		msg=msg.assoc(Fields.TOKEN,tokenKey);
		msg=msg.assoc(Fields.USER,userKey);
		msg=msg.assoc(Fields.AMOUNT,RT.str(amount));
		msg=msg.assoc(Fields.NEW_BALANCE,RT.str(newBalance));
		this.postAuditMessage(Engine.getRequest(),msg);
		return newBalance;
 	}
	
	@SuppressWarnings("unchecked")
	public synchronized AInteger subtractVirtualCredit(AString tokenKey, AString userKey, AInteger amount) throws PaymentException {
		if (amount.isNegative()) throw new IllegalArgumentException("Cannot subtract negative credit: "+amount);
		AInteger current=getVirtualCredit(tokenKey, userKey);
		if (current==null) current=CVMLong.ZERO;
		AInteger newBalance=current.sub(amount);
		if (newBalance.isNegative()) throw new PaymentException("Cannot remove more than total credit balance: current="+current+" removed="+amount+ " for user="+userKey);
		this.stateCursor.update(state->(AMap<AString, ACell>) RT.assocIn(state, newBalance, Fields.CREDITS,userKey,tokenKey));
		
		AMap<AString,?> msg=getBaseLogMessage("DEBIT");
		msg=msg.assoc(Fields.TOKEN,tokenKey);
		msg=msg.assoc(Fields.USER,userKey);
		msg=msg.assoc(Fields.AMOUNT,RT.str(amount));
		msg=msg.assoc(Fields.NEW_BALANCE,RT.str(newBalance));
		this.postAuditMessage(Engine.getRequest(),msg);
		return newBalance;
 	}
	
	/**
	 * Gets the basic log message for this server
	 * @param type
	 * @return Map suitable as base log record, with standard fields pre-populated
	 */
	public AMap<AString,?> getBaseLogMessage(String type) {
		AMap<AString,?> msg=Maps.of(
				Fields.LOG_TYPE,type,
				Fields.TS,getTimestampString(),
				Fields.SERVER,getServerField());
		return msg;
	}
	
	// This caches a server identifier for the purposes of logging
	private AString serverField=null;
	private AString getServerField() {
		if (serverField==null) {
			AString fld=RT.getIn(config, Fields.URL);
			if (fld==null) fld=Strings.create("http://localhost:8080");
			serverField=fld;
		}
		return serverField;
	}



	/**
	 * Gets an immutable snapshot of the current tokengine state;
	 * @return State data structure
	 */
	public ACell getStateSnapshot() {
		return stateCursor.get();
	}

	public String getTimestampString() {
		String timestamp = java.time.format.DateTimeFormatter
			    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
			    .format(java.time.Instant.now().atZone(java.time.ZoneOffset.UTC));
		return timestamp;
	}

	/**
	 * Gets the current peer state snapshot for the embedded Convex peer
	 * @return Peer snapshot
	 */
	public Peer getPeer() {
		return convex.getLocalServer().getPeer();
	}

	static ThreadLocal<AString> requestID=new ThreadLocal<>();
	public static void beginRequest() {
		requestID.set(Blobs.createRandom(16).toCVMHexString());
	}
	
	public static AString getRequest() {
		return requestID.get();
	}
	
	public static void endRequest() {
		requestID.set(null);
	}

}
