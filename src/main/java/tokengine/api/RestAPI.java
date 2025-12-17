package tokengine.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.AInteger;
import convex.core.json.JSON5Reader;
import convex.core.lang.RT;
import convex.core.util.JSON;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.PaymentRequiredResponse;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import tokengine.Engine;
import tokengine.Fields;
import tokengine.adapter.AAdapter;
import tokengine.api.model.BalanceRequest;
import tokengine.api.model.DepositRequest;
import tokengine.api.model.PayoutRequest;
import tokengine.api.model.TransferRequest;
import tokengine.exception.PaymentException;

public class RestAPI extends ATokengineAPI {
	
	protected static final Logger log = LoggerFactory.getLogger(RestAPI.class.getName());

	
	private static final String TOKENGINE_TAG="TokEngine";
	
	protected Engine engine;

	public RestAPI(Engine engine) {
		this.engine=engine;
	}

	private static final String ROUTE = "/api/v1/";

	@Override
	public void addRoutes(Javalin javalin) {
		javalin.get(ROUTE + "status", this::getStatus);
		javalin.get(ROUTE + "adapters", this::getAdapters);

		javalin.post(ROUTE + "balance", this::getBalance);
		
		javalin.post(ROUTE + "credit", this::getCredit);

		
		javalin.post(ROUTE + "transfer", this::postTransfer);
		javalin.post(ROUTE + "payout", this::postPayout);
		javalin.post(ROUTE + "wrap", this::postWrap);
		javalin.post(ROUTE + "deposit", this::postDeposit);
		
		javalin.get(ROUTE + "config", this::getConfig);

	}

	
	@OpenApi(path = ROUTE + "status", 
			methods = HttpMethod.GET, tags = {
			TOKENGINE_TAG }, 
			summary = "Get a quick TokEngine status report", operationId = "status",
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Status returned"),})
	protected void getStatus(Context ctx) {
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result(JSON.toString(engine.getStatus()));
		ctx.status(200);
	}
	
	@OpenApi(path = ROUTE + "config", 
			methods = HttpMethod.GET, tags = {
			TOKENGINE_TAG }, summary = "Get the tokengine configuration", operationId = "config",
					responses = {
							@OpenApiResponse(
									status = "200", 
									description = "Config returned")})
	protected void getConfig(Context ctx) {
		setJSONResult(ctx,engine.getConfig());
		ctx.status(200);
	}
	
	@OpenApi(path = ROUTE + "adapters", 
			methods = HttpMethod.GET, tags = {
			TOKENGINE_TAG }, summary = "Get a list of current DLT adapters installed", operationId = "adapters",
					responses = {
							@OpenApiResponse(
									status = "200", 
									description = "Adapters listed"),})
	protected void getAdapters(Context ctx) {
		ctx.header("Content-type", ContentTypes.JSON);
		
		ArrayList<?> handlers=engine.getAdapterConfigs();
		HashMap<String,Object> data=new HashMap<>();
		data.put("items",handlers);
		data.put("count",handlers.size());
		ctx.result(JSON.toString(data));
		ctx.status(200);
	}
	
	@OpenApi(path = ROUTE + "balance", 
			methods = HttpMethod.POST, 
			tags = {TOKENGINE_TAG }, 
			summary = "Queries the on-chain balance of a token", 
			operationId = "balance",
			requestBody = @OpenApiRequestBody(
					description = "Balance request, must provide a network (alias or CAIP-2 chainID), token (symbol alias or CAIP-19 token ID) and an address. TokEngine aliases and defined symbols may be used.", 
					content = {@OpenApiContent(
							from = BalanceRequest.class,  
							type = "application/json", 
							exampleObjects = {
									@OpenApiExampleProperty(name = "source", objects= {
											@OpenApiExampleProperty(name = "account", value="#11"),
											@OpenApiExampleProperty(name = "network", value="convex"),
											@OpenApiExampleProperty(name = "token", value="CVM")}) })}
						),
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Balance returned"),
					@OpenApiResponse(
							status = "400", 
							description = "Unable to query balance"),
					@OpenApiResponse(
							status = "503", 
							description = "DLT network unavailable for balance query")})
	protected void getBalance(Context ctx) {
		AMap<AString,ACell> req=parseRequest(ctx);
		AMap<AString,ACell> src = RT.ensureMap(req.get(Fields.SOURCE));
		if (src==null) throw new BadRequestResponse("Expected 'source' object specifying token");
		
		AString network=RT.ensureString(src.get(Fields.NETWORK));
		if (network==null) throw new BadRequestResponse("Expected 'network' property for source");
		AAdapter<?> adapter=engine.getAdapter(network);
		if (adapter==null) throw new BadRequestResponse("Can't find network: "+network);
		try {
			String token=RT.str(src.get(Fields.TOKEN)).toString();
			String address=RT.str(src.get(Fields.ACCOUNT)).toString();
			AInteger bal=adapter.getBalance(token,address);
			log.info("Querying balance on network: "+network +" token: "+token+" account: "+address + " bal="+bal);
			prepareResult(ctx,Result.value(bal));
		} catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
			throw new BadRequestResponse(e.toString());
		}

	}
	
	@OpenApi(path = ROUTE + "credit", 
			methods = HttpMethod.POST, 
			tags = {TOKENGINE_TAG}, 
			summary = "Queries the virtual balance of a token. This will be the sum of deposits less payouts for a given account.", 
			operationId = "credit",
			requestBody = @OpenApiRequestBody(
					description = "TokEngine virtual credit request, must provide a token (CAIP-19) and an address. TokEngine aliases and defined symbols may be used.", 
					content = {@OpenApiContent(
							from = BalanceRequest.class,  
							type = "application/json", 
							exampleObjects = {
									@OpenApiExampleProperty(name = "source", objects= {
											@OpenApiExampleProperty(name = "account", value="#11"),
											@OpenApiExampleProperty(name = "network", value="convex"),
											@OpenApiExampleProperty(name = "token", value="cad29:72")})})}),
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Virtual credit returned"),
					@OpenApiResponse(
							status = "400", 
							description = "Bad request, e.g. badly formatted account")})
	protected void getCredit(Context ctx) {
		try {
			AMap<AString,ACell> req=parseRequest(ctx);
			AMap<AString,ACell> src = RT.ensureMap(req.get(Fields.SOURCE));
			if (src==null) throw new BadRequestResponse("Expected 'source' object specifying token");
			
			// Ensure we have a valid network adapter
			AString network=RT.ensureString(src.get(Fields.NETWORK));
			if (network==null) throw new BadRequestResponse("Expected 'network' property for source");
			AAdapter<?> adapter=engine.getAdapter(network);
			if (adapter==null) throw new BadRequestResponse("Can't find network: "+network);
			
			AString token = RT.ensureString(src.get(Fields.TOKEN));
			if (token==null) throw new BadRequestResponse("Expected 'token' property for source");
			
			AString tokenKey=engine.getTokenKey(network, src.get(Fields.TOKEN).toString());
			if (tokenKey==null) throw new BadRequestResponse("Token not found for source: "+src);
			
			AString address=RT.getIn(src, Fields.ACCOUNT);
			AString canonicalAddress=adapter.parseUserKey(address.toString());
			
			AInteger bal=engine.getVirtualCredit(tokenKey,canonicalAddress);
			log.debug("Querying balance on network: "+network +" token: "+tokenKey+" account: "+canonicalAddress + " bal="+bal);
			prepareResult(ctx,Result.value(bal));
		} catch (Exception e) {
			throw new BadRequestResponse(e.getMessage());
		}
	}
	
	private AMap<AString,ACell> parseRequest(Context ctx) {
		try {
			ACell data=JSON5Reader.read(ctx.bodyInputStream());
			AMap<AString,ACell> m=RT.ensureMap(data);
			if (m==null) throw new BadRequestResponse("JSON Object expected as request body");
			return m;
		} catch (Exception e) {
			throw new BadRequestResponse("JSON Parsing failed: "+e.getMessage());
		}
	}


	@OpenApi(path = ROUTE + "transfer", 
			methods = HttpMethod.POST, tags = {
			TOKENGINE_TAG }, summary = "Transfer a quantity of equivalent tokens", operationId = "transfer",
					requestBody = @OpenApiRequestBody(
							description = "Transfer request, must provide a source, destination and quantity", 
							content = {@OpenApiContent(
											from = TransferRequest.class,  
											type = "application/json", 
											exampleObjects = {
													@OpenApiExampleProperty(name = "source", objects= {
															@OpenApiExampleProperty(name = "account", value="#11"),
															@OpenApiExampleProperty(name = "network", value="convex"),
															@OpenApiExampleProperty(name = "token", value="CVM")
													}),
													@OpenApiExampleProperty(name = "destination", objects= {
															@OpenApiExampleProperty(name = "account", value="#12"),
															@OpenApiExampleProperty(name = "network", value="convex"),
															@OpenApiExampleProperty(name = "token", value="WCVM")
													}),
													@OpenApiExampleProperty(name = "deposit", objects= {
															@OpenApiExampleProperty(name = "tx", value="0x9d3a3663d32b9ff5cf2d393e433b7b31489d13b398133a35c4bb6e2085bd8e83"),
															@OpenApiExampleProperty(name = "msg", value="Transfer 1000 to #12 on convex"),
															@OpenApiExampleProperty(name = "sig", value="0xdd48188b1647010d908e9fed4b6726cebd0d65e20f412b8b9ff4868386f05b0a28a9c0e35885c95e2322c2c670743edd07b0e1450ae65c3f6708b61bb3e582371c")
													}), 
													@OpenApiExampleProperty(name = "quantity", value = "1000") })}
						))
	protected void postTransfer(Context ctx) {
		try {
			Engine.beginRequest();
			AMap<AString,ACell> req = parseRequest(ctx);
			AInteger deposited = doDeposit(req);
			AString o=doPayout(req);
			log.info("Transfer of tokens, deposited amout="+deposited+"  payout tx="+o);
			
			Result r = Result.value(o);
			prepareResult(ctx, r);
		} catch (Exception e) {
			log.warn("Could not confirm deposit: "+e.getMessage());
			throw new PaymentRequiredResponse("Could not confirm deposit: "+e.getMessage());
		} finally {
			Engine.endRequest();
		}
	}
	
	@OpenApi(path = ROUTE + "payout", 
			methods = HttpMethod.POST, tags = {
			TOKENGINE_TAG }, summary = "Payout a quantity of owned tokens", operationId = "payout",
					requestBody = @OpenApiRequestBody(
							description = "Payout request, must provide a destination and quantity", 
							content = {@OpenApiContent(
											from = PayoutRequest.class,  
											type = "application/json", 
											exampleObjects = {
													@OpenApiExampleProperty(name = "source", objects= {
															@OpenApiExampleProperty(name = "account", value="#11"),
															@OpenApiExampleProperty(name = "network", value="convex:test"),
															@OpenApiExampleProperty(name = "token", value="WCVM")
													}),
													@OpenApiExampleProperty(name = "destination", objects= {
															@OpenApiExampleProperty(name = "account", value="#13"),
															@OpenApiExampleProperty(name = "network", value="convex:test"),
															@OpenApiExampleProperty(name = "token", value="CVM")
													}),
													@OpenApiExampleProperty(name = "deposit", objects= {
															@OpenApiExampleProperty(name = "tx", value="0x9d3a3663d32b9ff5cf2d393e433b7b31489d13b398133a35c4bb6e2085bd8e83"),
															@OpenApiExampleProperty(name = "msg", value="Transfer 100000 to #13 on convex"),
															@OpenApiExampleProperty(name = "sig", value="0xdd48188b1647010d908e9fed4b6726cebd0d65e20f412b8b9ff4868386f05b0a28a9c0e35885c95e2322c2c670743edd07b0e1450ae65c3f6708b61bb3e582371c")
													}),
													@OpenApiExampleProperty(name = "quantity", value = "1000000") })}
						),
					responses = {
							@OpenApiResponse(
									status = "200", 
									description = "Payout succeeded"),
							@OpenApiResponse(
									status = "401", 
									description = "Payout not authorised"),
							@OpenApiResponse(
									status = "400", 
									description = "Payout failed, e.g. insufficient virtual balance")})
	protected void postPayout(Context ctx) {
		try {
			Engine.beginRequest();
			AMap<AString,ACell> req=parseRequest(ctx);
			AString r = doPayout(req);
			// log.warn("Paying out on network: "+chainID +" token: "+token+" account: "+address + " quantity="+q);
			prepareResult(ctx,Result.value(r));
		} finally {
			Engine.endRequest();
		}
	}


	private AString doPayout(AMap<AString, ACell> req) {
		AMap<AString,ACell> src = RT.ensureMap(req.get(Fields.SOURCE));
		if (src==null) throw new BadRequestResponse("Expected 'source' object specifying payor");
		AString srcUserKey=RT.ensureString(src.get(Fields.ACCOUNT));
		if (srcUserKey==null) throw new BadRequestResponse("Expected 'source.account' string identifying user");
	
		AInteger q= AInteger.parse(req.get(Fields.QUANTITY));
		if (q==null) throw new BadRequestResponse("Expected 'quantity' as valid integer amount");
		
		AMap<AString,ACell> dep = RT.ensureMap(req.get(Fields.DEPOSIT));
		if (dep==null) throw new BadRequestResponse("Expected 'deposit' object prviding signed instruction");
		
		ACell network=src.get(Fields.NETWORK);
		if (network==null) throw new BadRequestResponse("Expected 'network' property for source");
		AString srcChainID=RT.str(network);
		AAdapter<?> adapter=engine.getAdapter(srcChainID);
		if (adapter==null) throw new BadRequestResponse("Can't find source network: "+srcChainID);
		
		
		AMap<AString,ACell> dest = RT.ensureMap(req.get(Fields.DESTINATION));
		if (dest==null) throw new BadRequestResponse("Expected 'destination' object specifying payout account");
		AString destUserKey=RT.ensureString(dest.get(Fields.ACCOUNT));
		if (destUserKey==null) throw new BadRequestResponse("Expected 'dest.account' string identifying user");
		
		ACell destNetwork=dest.get(Fields.NETWORK);
		if (destNetwork==null) throw new BadRequestResponse("Expected 'network' property for destination");
		AString destChainID=RT.str(destNetwork);
		AAdapter<?> destAdapter=engine.getAdapter(destChainID);
		if (destAdapter==null) throw new BadRequestResponse("Can't find destination network: "+destChainID);

		// Subtract virtual credit
		String token=RT.str(src.get(Fields.TOKEN)).toString();
		AString tokenKey=engine.getTokenKey(adapter, token);
		AString canonicalAddress=adapter.parseUserKey(srcUserKey.toString());
		engine.subtractVirtualCredit(tokenKey, canonicalAddress, q);
		
		// Payout on destination network
		String destToken=RT.str(dest.get(Fields.TOKEN)).toString();
		AString result = engine.makePayout(destUserKey.toString(), destToken, destAdapter, q,dep);
		// log.warn("Payout made: "+r);
		return result;
	}
	
	
	
	@OpenApi(path = ROUTE + "wrap", 
			methods = HttpMethod.POST, 
			tags = {TOKENGINE_TAG }, 
			summary = "Wrap a quantity of tokens", operationId = "wrap")
	protected void postWrap(Context ctx) {
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result("{\"status\":\"OK\"}");
		ctx.status(200);
	}
	
	@OpenApi(path = ROUTE + "deposit", 
			methods = HttpMethod.POST, 
			tags = {TOKENGINE_TAG }, 
			summary = "Deposit tokens into the system", 
			operationId = "deposit",
			requestBody = @OpenApiRequestBody(
					description = "Deposit request, must provide a source and despsit proof. Token may be a CAIP-19 token ID, a symbol or alias. Deposit transaction must be paid to the operator's receiver address on the target network.", 
					content = {@OpenApiContent(
									type = "application/json", 
									from = DepositRequest.class,  
									exampleObjects = {
											@OpenApiExampleProperty(name = "source", objects= {
													@OpenApiExampleProperty(name = "account", value="0xa72018ba06475aCa284ED98AB0cE0E07878521a3"),
													@OpenApiExampleProperty(name = "network", value="eip155:11155111"),
													@OpenApiExampleProperty(name = "token", value="erc20:0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238")
											}),
											@OpenApiExampleProperty(name = "deposit", objects= {
													@OpenApiExampleProperty(name = "tx", value="0x9d3a3663d32b9ff5cf2d393e433b7b31489d13b398133a35c4bb6e2085bd8e83")											}) 
									})}),
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Deposit accepted"),
					@OpenApiResponse(
							status = "400", 
							description = "Bad request, see message for reason(s)"),
					@OpenApiResponse(
							status = "402", 
							description = "Deposit not accepted, verified payment required")})
	protected void postDeposit(Context ctx) {
		try {
			Engine.beginRequest();
			AMap<AString, ACell> req = parseRequest(ctx);
			AInteger deposited = doDeposit(req);
			
			// For now, we'll treat deposit similar to a transfer, using the engine's transfer functionality
			// log.warn("Deposit made: "+deposited+" "+token+" with proof: "+dep);
			Result r = Result.value(deposited);
			prepareResult(ctx, r);
		} catch (PaymentException e) {
			throw new PaymentRequiredResponse("Could not confirm deposit: "+e.getMessage());
		} catch (Exception e) {
			log.info("Could not confirm deposit: ",e);
			throw new BadRequestResponse("Could not make deposit: "+e.getMessage());
		} finally {
			Engine.endRequest();
		}
		
	}

	/**
	 * Perform deposit
	 * @param ctx
	 * @return
	 * @throws IOException
	 */
	private AInteger doDeposit(AMap<AString,ACell> req) throws IOException, PaymentException {
		AMap<AString,ACell> src = RT.ensureMap(req.get(Fields.SOURCE));
		if (src == null) throw new BadRequestResponse("Expected 'source' object specifying incoming token");
		//AInteger q = AInteger.parse(req.get(Strings.create("quantity")));
		//if (q == null) throw new BadRequestResponse("Expected 'quantity' as valid integer amount");
		
		AMap<AString,ACell> dep = RT.ensureMap(req.get(Fields.DEPOSIT));
		if (dep == null) throw new BadRequestResponse("Expected 'deposit' object specifying transaction proof");

		ACell network = src.get(Fields.NETWORK);
		if (network == null) throw new BadRequestResponse("Expected 'source.network' property");
		AString chainID = RT.str(network);
		AAdapter<?> adapter = engine.getAdapter(chainID);
		if (adapter == null) throw new BadRequestResponse("Can't find network: " + chainID);
		
		// Validate token
		AString tokenAS=RT.ensureString(src.get(Fields.TOKEN));
		if (tokenAS == null) throw new BadRequestResponse("Expected 'source.token' value specifying token");
		String token = tokenAS.toString();
		
		// Validate sender address
		AString addressAS=RT.ensureString(src.get(Fields.ACCOUNT));
		if (addressAS == null) throw new BadRequestResponse("Expected 'source.account' value specifying account on network "+chainID);
		String address = addressAS.toString();
		
		// Check transaction validity
		AInteger deposited=engine.makeDeposit(adapter,token,address,dep);
		if (deposited==null) {
			throw new PaymentRequiredResponse("Failed to validate deposit: "+dep);
		}
		return deposited;
	}


}
