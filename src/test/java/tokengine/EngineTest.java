package tokengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.prim.*;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.util.ConfigUtils;
import tokengine.adapter.AAdapter;

/**
 * Tests for a standalone Engine
 * 
 * We test this directly to ensure correct logical behaviour without the API layer
 */
@TestInstance(Lifecycle.PER_CLASS)
public class EngineTest {
	
	Engine engine=null;
	
	@BeforeAll public void setup() throws Exception {
		AMap<AString,ACell> config=ConfigUtils.readConfig(EngineTest.class.getResourceAsStream("/tokengine/config-test.json"));
		if (config==null) throw new IllegalStateException("Can't have null config for tests");
		engine = Engine.launch(config); // default config
	}
	
	@Test public void testExampleEngine() throws Exception {
		Engine e = engine;
		assertEquals(3,e.getAdapters().size());		
	}
	
	@Test public void testAuditMessage() {
		assertNotNull(engine.kafka);
		assertTrue(engine.postAuditMessage(Fields.TEST,Maps.of("test-run",engine.getTimestampString())));
	}
	
	@Test public void testAdapterProperties() {
		for (AAdapter<?> a:engine.getAdapters()) {
			ACell opAddr=a.getOperatorAddress();
			assertNotNull(opAddr);
			
			Index<AString, AMap<AString, ACell>> tokens = a.getTokens();
			long tc=tokens.count();
			for (long i=0; i<tc; i++) {
				MapEntry<AString, AMap<AString, ACell>> me=tokens.entryAt(i);
				AString toKey=me.getKey();
				assertEquals(toKey,a.lookupCAIPAssetID(toKey.toString()));
				
				ACell assetID=a.parseAssetID(toKey.toString());
				assertNotNull(assetID);
				assertEquals(toKey,a.toCAIPAssetID(assetID));
				
				AMap<AString, ACell> rec=me.getValue();
				assertNotNull(rec);
				
				try {
					a.getOperatorBalance(toKey);
				} catch (IOException e) {
					// might fail due to IO, if so ignore
				}
			}	
		}
	}
	
	
	@Test public void testDepositCredit() throws Exception {
		Engine e = engine;
		
		// use "true" as asset key
		AString assetKey=Strings.TRUE;
		AString userKey=Strings.create("ImplausibleTestUser");
		AInteger DEPOSIT=CVMLong.create(666);
		AInteger WITHDRAWAL=CVMLong.create(300);
		
		// Credit should be initially null
		assertNull(e.getVirtualCredit(assetKey, userKey));
		// System.out.println(e.getStateSnapshot());
		
		e.addVirtualCredit(assetKey,userKey,DEPOSIT);
		assertEquals(DEPOSIT,e.getVirtualCredit(assetKey, userKey));
		
		
		e.subtractVirtualCredit(assetKey, userKey, WITHDRAWAL);
		assertEquals(DEPOSIT.sub(WITHDRAWAL),e.getVirtualCredit(assetKey, userKey));
	}
	
	@AfterAll public void shutdown() {
		engine.close();
		assertFalse(engine.postAuditMessage(Fields.TEST,Maps.of("test-run",engine.getTimestampString())));
	}

}
