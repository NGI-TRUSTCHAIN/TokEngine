package tokengine.adapter.tezos;

import java.util.Map;

import org.bouncycastle.util.Arrays;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519Signature;
import convex.core.crypto.Hashing;
import convex.core.data.ABlob;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.crypto.util.Base58Check;

/**
 * Utility functions for Tezos-specific operations
 */
public class TezosUtils {
	
	/** Magic prefix for tz1 addresses */
	static final Blob TZ1_PREFIX=Blob.fromHex("06A19F");
	
	/** Magic prefix for tz2 addresses */
	static final Blob TZ2_PREFIX=Blob.fromHex("06a1a1");

	/** Magic prefix for tz2 addresses */
	static final Blob TZ3_PREFIX=Blob.fromHex("06a1a4");
	
	public static final Blob EDPK_PREFIX = Blob.fromHex("0D0f25d9");
	
	static final Map<String,Blob> PREFIXES=Maps.hashMapOf("tz1",TZ1_PREFIX,"tz2",TZ2_PREFIX,"tz3",TZ3_PREFIX);
   
	static final int ADDRESS_LENGTH_20=20;


	
    /**
     * Converts an AKeyPair to a valid Tezos address (tz1 format)
     * @param keyPair The Ed25519 key pair
     * @return AString containing the Tezos address
     */
    public static AString keyPairToTezosAddress(AKeyPair keyPair) {
        // Get the account key from the key pair
        convex.core.data.AccountKey accountKey = keyPair.getAccountKey();
        
        byte[] hash=publicKeyToHash(accountKey);
        
        // Create the address bytes: [0xA19F] + publicKeyHash (20 bytes)
        byte[] addressBytes = new byte[3+ADDRESS_LENGTH_20];
        TZ1_PREFIX.getBytes(addressBytes,0);
        System.arraycopy(hash, 0, addressBytes, 3, ADDRESS_LENGTH_20);
 
        	// Encode with Base58Check
        String address = Base58Check.encode(addressBytes);
        
        return Strings.create(address);
    }
    
	public static byte[] publicKeyToHash(ABlob publicKey) {
	      // Convert hex string to bytes prefixed with the byte 0x00
        byte[] publicKeyBytes = new byte[33];
        publicKeyBytes[0]=0x00;
        publicKey.getBytes(publicKeyBytes,1);
        
        // For tz1 addresses, we need to hash the public key  with Blake2b
        byte[] publicKeyHash = blake2bHash(publicKeyBytes);
        
       
        return publicKeyHash;
	}

     
    /**
     * Validates an Ed25519 signature given a Tezos address, message, and signature
     * @param address The Tezos address (tz1 format)
     * @param message The message that was signed
     * @param signature The Ed25519 signature
     * @return true if the signature is valid, false otherwise
     */
    public static boolean validateEd25519Signature(AString address, ABlob publicKey, ABlob message, ABlob signature) {
        try {
            // Decode the address to get the public key hash
            byte[] addressBytes = getAddressBytes(address);

            // For Ed25519 verification, we need to reconstruct the public key from the hash
            // This is a simplified implementation - in practice, you'd need to store the full public key
            // or use a different approach for signature verification
            
            // Convert message and signature to byte arrays
            byte[] messageBytes = message.getBytes();
            byte[] signatureBytes = signature.getBytes();
            
            // Verify the signature using Ed25519
            return verifyEd25519Signature(addressBytes, publicKey, messageBytes, signatureBytes);
            
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets the raw address bytes from a Tezos tz1
     * @param address
     * @return array of bytes
     */
	public static byte[] getAddressBytes(AString address) {
		return getAddressBytes(address.toString());	
	}

	/**
     * Gets the raw address bytes from a Tezos address
     * @param s Tezos address e.g. starting with "tz1"
     * @return array of bytes
     */
	public static byte[] getAddressBytes(String s) {
		String pid=s.substring(0, 3);
		Blob prefix=PREFIXES.get(pid);
		if (prefix==null) {
			throw new IllegalArgumentException("Invalid Tezos address, should start with 'tz(1-3)': "+s);
		}
		
		byte[] decodedBytes = decodeRemovingPrefix(prefix,s);
		if (decodedBytes.length != ADDRESS_LENGTH_20) {
		    throw new IllegalArgumentException("Wrong number of bytes in tezos addres: "+decodedBytes.length); // Invalid address format
		}
		
       return decodedBytes;
	}
	
	public static byte[] decodeRemovingPrefix(Blob prefix, String encoded) {
		int pl=prefix.size();
		byte[] decodedBytes = Base58Check.decode(encoded);
		if (!prefix.equalsBytes(decodedBytes, 0)) {
			throw new IllegalArgumentException("Wrong prefix, expected "+prefix);
		}
		int n=decodedBytes.length;
		return Arrays.copyOfRange(decodedBytes, pl, n);
	}
    
    /**
     * Computes Blake2b hash of the input bytes
     * @param input The bytes to hash
     * @return 20-byte hash
     */
    private static byte[] blake2bHash(byte[] input) {
        byte[] hash = Hashing.blake2b160(input).getInternalArray();
        return hash;
    }
    
    /**
     * Verifies an Ed25519 signature
     * @param publicKeyHash The public key hash
     * @param publicKey 
     * @param message The message that was signed
     * @param signature The signature to verify
     * @return true if signature is valid
     */
    private static boolean verifyEd25519Signature(byte[] publicKeyHash, ABlob publicKey, byte[] message, byte[] signature) {
        Ed25519Signature sig=Ed25519Signature.wrap(signature);
        AccountKey pk=AccountKey.create(publicKey);
        if (sig.verify(Blob.wrap(message), pk)) {
        	return Arrays.areEqual(publicKeyHash, publicKeyToHash(publicKey));
        } else {
        	return false;
        }
    }


}
