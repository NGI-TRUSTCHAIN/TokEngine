package tokengine.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;
import convex.java.ARESTClient;
import tokengine.Fields;
import tokengine.exception.ResponseException;

public class Client extends ARESTClient {

	private final HttpClient httpClient;

	public Client(URI host) {
		super(host,"/api/v1/");
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	}

	/**
	 * Create a client instance for the given host
	 * @param uri Host URI e.g. URI.create("https://foo.org")
	 * @return New client instance
	 */
	public static Client create(URI uri) {
		return new Client(uri);
	}

	/**
	 * Gets the status of the TokEngine server
	 * @return Future for the status result
	 */
	public Future<ACell> getStatus() {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("status"))
			.GET()
			.build();
		Future<ACell> resultFuture = doJSONRequest(req);
		return resultFuture;
	}
	
	/**
	 * Gets the config of the TokEngine server
	 * @return Future for the config data structure
	 */
	public Future<ACell> getConfig() {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("config"))
			.GET()
			.build();
		Future<ACell> resultFuture = doJSONRequest(req);
		return resultFuture;
	}
	
	/**
	 * Gets the balance of a token for a holder on an underlying DLT network
	 * @param network The DLT network to query. Can be a network alias or a canonical Chain ID e.g. "eip155:11155111"
	 * @param holder The account address to query
	 * @param assetId The token/asset identifier in CAIP19 format or asset alias
	 * @return Future for the balance as AInteger
	 */
	public CompletableFuture<AInteger> getBalance(String network, String assetId, String holder) {
		// Create the request body structure
		AMap<AString, ACell> source = Maps.of(
			Strings.create("account"), Strings.create(holder),
			Strings.create("network"), Strings.create(network), // Default network, could be parameterised
			Strings.create("token"), Strings.create(assetId)
		);
		AMap<AString, ACell> requestBody = Maps.of(
			Strings.create("source"), source
		);
		
		String jsonBody = JSON.toString(requestBody);
		
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("balance"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
			.build();
		
		CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		return future.thenApplyAsync(resp -> {
			int code = resp.statusCode();
			if ((code / 100) == 2) {
				ACell result = JSON.parse(resp.body());
				// The API returns a Result.value(balance), so we need to extract the AInteger
				if (result instanceof AMap) {
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> resultMap = (AMap<AString, ACell>) result;
					ACell value = resultMap.get(Strings.create("value"));
					if (value instanceof AInteger) {
						return (AInteger)value;
					}
				}
				throw new ResponseException("Unexpected response format: "+result, resp);
			}
			throw new ResponseException("Failed request with status "+code+" and data "+resp.body(), resp);
		});
	}
	
	/**
	 * Gets the virtual credit of a token for a holder with TokEngine
	 * @param holder The account address to query
	 * @param network The network to query. Can be a network alias or a canonical Chain ID e.g. "eip155:11155111"
	 * @param assetId The token/asset identifier in CAIP19 format or asset alias
	 * @return Future for the balance as AInteger
	 */
	public CompletableFuture<AInteger> getCredit(String holder, String network, String assetId) {
		// Create the request body structure
		AMap<AString, ACell> source = Maps.of(
			Strings.create("account"), Strings.create(holder),
			Strings.create("network"), Strings.create(network), // Default network, could be parameterised
			Strings.create("token"), Strings.create(assetId)
		);
		AMap<AString, ACell> requestBody = Maps.of(
			Strings.create("source"), source
		);
		
		String jsonBody = JSON.toString(requestBody);
		
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("credit"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
			.build();
		
		CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		return future.thenApplyAsync(resp -> {
			int code = resp.statusCode();
			if ((code / 100) == 2) {
				ACell result = JSON.parse(resp.body());
				// The API returns a Result.value(balance), so we need to extract the AInteger
				if (result instanceof AMap) {
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> resultMap = (AMap<AString, ACell>) result;
					ACell value = resultMap.get(Fields.VALUE);
					if (value instanceof AInteger) {
						return (AInteger)value;
					} else if (value==null) {
						return CVMLong.ZERO;
					}
				}
				throw new ResponseException("Unexpected response format: "+result, resp);
			}
			throw new ResponseException("Failed request with status "+code+" and data "+resp.body(), resp);
		});
	}
	
	public CompletableFuture<AInteger> deposit(Object txID, String holder, String network, String assetId) {
		AMap<AString, ACell> source = Maps.of(
			Fields.NETWORK, Strings.create(network), // Default network, could be parameterised
			Fields.TOKEN, Strings.create(assetId),
			Fields.ACCOUNT, Strings.create(holder)
		);
		AMap<AString, ACell> requestBody = Maps.of(
			Fields.SOURCE, source,
			Fields.DEPOSIT,Maps.of(Fields.TX,txID)
		);

		String jsonBody = JSON.toString(requestBody);
		
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("deposit"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
			.build();
		
		CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		return future.thenApplyAsync(resp -> {
			int code = resp.statusCode();
			if ((code / 100) == 2) {
				ACell result = JSON.parse(resp.body());
				// The API returns a Result.value(balance), so we need to extract the AInteger
				if (result instanceof AMap) {
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> resultMap = (AMap<AString, ACell>) result;
					ACell value = resultMap.get(Fields.VALUE);
					if (value instanceof AInteger) {
						return (AInteger)value;
					} else if (value==null) {
						return CVMLong.ZERO;
					}
				}
				throw new ResponseException("Unexpected response format: "+result, resp);
			}
			
			throw new ResponseException("Failed request with status "+code+" and data "+resp.body(), resp);
		});
	}
	
	/**
	 * Makes a HTTP request as a CompletableFuture
	 * @param request Request object
	 * @return Future to be filled with JSON response.
	 */
	protected CompletableFuture<ACell> doJSONRequest(HttpRequest request) {
		CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		return future.thenApplyAsync(resp->{
			int code=resp.statusCode();
			if ((code/100)==2) {
				return JSON.parse(resp.body());
			}
			throw new ResponseException("Failed request",resp); 
		});
	}

	/**
	 * Makes a HTTP payout request as a CompletableFuture
	 * @return Future with a string containing the payout transaction hash
	 */
	public CompletableFuture<AInteger> payout(String fromUser, String fromNetwork, String fromToken, String toUser, String toNetwork, String toToken,String quantity) {
		AMap<AString, ACell> source = Maps.of(
			Fields.NETWORK, Strings.create(fromNetwork), // Default network, could be parameterised
			Fields.TOKEN, Strings.create(fromToken),
			Fields.ACCOUNT, Strings.create(fromUser)
		);
		AMap<AString, ACell> dest = Maps.of(
			Fields.NETWORK, Strings.create(toNetwork), // Default network, could be parameterised
			Fields.TOKEN, Strings.create(toToken),
			Fields.ACCOUNT, Strings.create(toUser)
		);
		AMap<AString, ACell> requestBody = Maps.of(
			Fields.SOURCE, source,
			Fields.DESTINATION,dest,
			Fields.QUANTITY,quantity
		);

		String jsonBody = JSON.toString(requestBody);
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("payout"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
			.build();
		
		CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		return future.thenApplyAsync(resp -> {
			int code = resp.statusCode();
			if ((code / 100) == 2) {
				ACell result = JSON.parse(resp.body());
				// The API returns a Result.value(balance), so we need to extract the AInteger
				if (result instanceof AMap) {
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> resultMap = (AMap<AString, ACell>) result;
					ACell value = resultMap.get(Fields.VALUE);
					if (value instanceof AInteger) {
						return (AInteger)value;
					} else if (value==null) {
						return CVMLong.ZERO;
					} else if (value instanceof AString) {
						return AInteger.parse(quantity);
					}
				}
				throw new ResponseException("Unexpected response format: "+result, resp);
			}
			
			throw new ResponseException("Failed request with status "+code+" and data "+resp.body(), resp);
		});
	}
}
