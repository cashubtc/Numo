#!/usr/bin/env python3
"""
Satocash Smart Card Interface - Complete Version
With card detection, AID discovery, and all Satocash functionality
"""

import hashlib
import hmac
import os
import struct
from enum import Enum, IntEnum

from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from smartcard.CardRequest import CardRequest
from smartcard.Exceptions import (
    CardConnectionException,  # Import the specific exception
)
from smartcard.util import toHexString


class ProofInfoType(Enum):
    # metadata_type constants
    METADATA_STATE = 0
    METADATA_KEYSET_INDEX = 1
    METADATA_AMOUNT_EXPONENT = 2
    METADATA_MINT_INDEX = 3
    METADATA_UNIT = 4

class Unit(Enum):
    EMPTY = 0
    SAT = 1
    MSAT = 2
    USD = 3
    EUR = 4

class Error(IntEnum):
    """
    Status Words (SW) for smart card operations,
    translated from Java constants.
    """
    PIN_FAILED = 0x63C0  # Entered PIN is not correct (includes number of tries remaining)
    OPERATION_NOT_ALLOWED = 0x9C03  # Required operation is not allowed in actual circumstances
    SETUP_NOT_DONE = 0x9C04  # Required setup is not done
    SETUP_ALREADY_DONE = 0x9C07  # Required setup is already done
    UNSUPPORTED_FEATURE = 0x9C05  # Required feature is not (yet) supported
    UNAUTHORIZED = 0x9C06  # Required operation was not authorized because of a lack of privileges
    NO_MEMORY_LEFT = 0x9C01  # There have been memory problems on the card
    OBJECT_NOT_FOUND = 0x9C08  # Required object is missing (DEPRECATED)
    INCORRECT_P1 = 0x9C10  # Incorrect P1 parameter
    INCORRECT_P2 = 0x9C11  # Incorrect P2 parameter
    SEQUENCE_END = 0x9C12  # No more data available
    INVALID_PARAMETER = 0x9C0F  # Invalid input parameter to command
    SIGNATURE_INVALID = 0x9C0B  # Verify operation detected an invalid signature
    IDENTITY_BLOCKED = 0x9C0C  # Operation has been blocked for security reason
    INTERNAL_ERROR = 0x9CFF  # For debugging purposes
    INCORRECT_INITIALIZATION = 0x9C13  # Incorrect initialization of method
    LOCK_ERROR = 0x9C30  # Lock error
    HMAC_UNSUPPORTED_KEYSIZE = 0x9C1E  # HMAC error: unsupported key size
    HMAC_UNSUPPORTED_MSGSIZE = 0x9C1F  # HMAC error: unsupported message size
    SECURE_CHANNEL_REQUIRED = 0x9C20  # Secure channel required
    SECURE_CHANNEL_UNINITIALIZED = 0x9C21  # Secure channel uninitialized
    SECURE_CHANNEL_WRONG_IV = 0x9C22  # Secure channel wrong IV
    SECURE_CHANNEL_WRONG_MAC = 0x9C23  # Secure channel wrong MAC
    PKI_ALREADY_LOCKED = 0x9C40  # PKI error: already locked
    NFC_DISABLED = 0x9C48  # NFC interface disabled
    NFC_BLOCKED = 0x9C49  # NFC interface blocked
    INS_DEPRECATED = 0x9C26  # For instructions that have been deprecated
    RESET_TO_FACTORY = 0xFF00  # CARD HAS BEEN RESET TO FACTORY
    DEBUG_FLAG = 0x9FFF  # For debugging purposes 2
    OBJECT_ALREADY_PRESENT = 0x9C60  # Satocash error: object already present
    SUCCESS = 0x9000 # Success

class MultiApduOperations(IntEnum):
    # For operations running on multiple APDUs
    OP_INIT = 0x01
    OP_PROCESS = 0x02
    OP_FINALIZE = 0x03


MAX_NB_PROOFS = 128

class SatocashException(Exception):
    """Custom exception for Satocash card errors."""
    def __init__(self, message, sw=None):
        super().__init__(message)
        self.sw = sw

class SecureChannel:
    def __init__(self):
        self.client_private_key = None
        self.client_public_key = None
        self.card_ephemeral_public_key = None
        self.card_authentikey_public_key = None
        self.shared_secret = None
        self.session_key = None
        self.mac_key = None
        self.initialized = False
        
        # Constants from the applet
        self.CST_SC_KEY = b'sc_key'
        self.CST_SC_MAC = b'sc_mac'
        self.SIZE_SC_IV = 16
        self.SIZE_SC_IV_RANDOM = 12
        self.SIZE_SC_IV_COUNTER = 4
        self.SIZE_SC_MACKEY = 20

        # Initialize IV
        self.iv_counter = 1
        self.iv_random = os.urandom(self.SIZE_SC_IV_RANDOM)

    def generate_client_keypair(self):
        """Generate client ECDH keypair using secp256k1"""
        self.client_private_key = ec.generate_private_key(ec.SECP256K1(), default_backend())
        self.client_public_key = self.client_private_key.public_key()
        assert len(self.get_client_public_key_bytes()) == 65
        return self.get_client_public_key_bytes()

    def get_client_public_key_bytes(self):
        """Get client public key in uncompressed format (65 bytes)"""
        assert self.client_public_key
        return self.client_public_key.public_bytes(
            encoding=serialization.Encoding.X962,
            format=serialization.PublicFormat.UncompressedPoint
        )

    def parse_card_response(self, response):
        """Parse the card's response from InitiateSecureChannel"""
        if len(response) < 6:
            raise SatocashException("Secure channel response too short", sw=None)
        
        pos = 0
        
        # Parse ephemeral key coordinate x
        coordx_size = struct.unpack('>H', bytes(response[pos:pos+2]))[0]
        pos += 2
        
        if coordx_size != 32:
            raise SatocashException(f"Unexpected coordinate size: {coordx_size}", sw=None)
        
        ephemeral_coordx = bytes(response[pos:pos+coordx_size])
        pos += coordx_size
        
        # Parse self-signature
        sig_size = struct.unpack('>H', bytes(response[pos:pos+2]))[0]
        pos += 2
        
        ephemeral_signature = bytes(response[pos:pos+sig_size])
        pos += sig_size
        
        # Parse authentikey signature
        sig2_size = struct.unpack('>H', bytes(response[pos:pos+2]))[0]
        pos += 2
        
        authentikey_signature = bytes(response[pos:pos+sig2_size])
        pos += sig2_size
        
        # Parse authentikey coordinate x
        if pos + 2 <= len(response):
            authentikey_coordx_size = struct.unpack('>H', bytes(response[pos:pos+2]))[0]
            pos += 2
            authentikey_coordx = bytes(response[pos:pos+authentikey_coordx_size])
        else:
            authentikey_coordx = None
        
        return {
            'ephemeral_coordx': ephemeral_coordx,
            'ephemeral_signature': ephemeral_signature,
            'authentikey_signature': authentikey_signature,
            'authentikey_coordx': authentikey_coordx
        }

    def recover_card_public_key(self, coordx, signature):
        """Recover the full public key from x-coordinate and signature"""
        
        # Try both possible y-coordinates (even/odd)
        for recovery_id in [0, 1]:
            try:
                point_bytes = (b'\x02' if recovery_id == 0 else b'\x03') + coordx
                
                public_key = ec.EllipticCurvePublicKey.from_encoded_point( ec.SECP256K1(), point_bytes )
                return public_key
            except Exception as e:
                print(f"Recovery attempt failed for recovery_id {recovery_id}: {e}")
                continue

        raise SatocashException("Could not recover public key", sw=None)

    def derive_keys(self, shared_secret):
        """Derive session key and MAC key from shared secret"""
        # Following the applet's key derivation:
        # HmacSha160.computeHmacSha160(shared_secret, CST_SC_KEY, mac_key_out)
        # HmacSha160.computeHmacSha160(shared_secret, CST_SC_MAC, session_key_out)
        
        # MAC key derivation (20 bytes)
        self.mac_key = hmac.new(
            shared_secret,
            self.CST_SC_MAC,
            hashlib.sha1
        ).digest()

        assert len(self.mac_key) == 20
        
        # Session key derivation (16 bytes for AES-128)
        session_key_full = hmac.new(
            shared_secret,
            self.CST_SC_KEY,
            hashlib.sha1
        ).digest()
        
        self.session_key = session_key_full[:16]  # AES-128 needs 16 bytes
        
        # print(f"Derived session key: {self.session_key.hex()}")
        # print(f"Derived MAC key: {self.mac_key.hex()}")

    def complete_handshake(self, card_response):
        """Complete the ECDH handshake with the card"""
        parsed = self.parse_card_response(card_response)
        
        # Recover card's ephemeral public key
        self.card_ephemeral_public_key = self.recover_card_public_key(
            parsed['ephemeral_coordx'], 
            parsed['ephemeral_signature']
        )
        
        # Perform ECDH to get shared secret
        assert self.client_private_key
        shared_secret = self.client_private_key.exchange(
            ec.ECDH(), 
            self.card_ephemeral_public_key
        )
        
        # Derive session and MAC keys
        self.derive_keys(shared_secret)
        
        self.initialized = True
        # print("✓ Secure channel established!")

    def generate_iv(self):
        """Generate IV for encryption (12 bytes random + 4 bytes counter)"""
        if not self.initialized:
            raise SatocashException("Secure channel not initialized", sw=None)
        
        # Update counter (must be odd for client->card)
        self.iv_counter += 2  # Ensure it's always odd
        
        # Generate new random part
        self.iv_random = os.urandom(self.SIZE_SC_IV_RANDOM)
        
        # Combine random + counter
        counter_bytes = struct.pack('>I', self.iv_counter)
        iv = self.iv_random + counter_bytes
        
        return iv

    def encrypt_command(self, command_apdu):
        """Encrypt a command APDU for secure channel"""
        if not self.initialized:
            raise SatocashException("Secure channel not initialized", sw=None)
        
        # Generate IV
        iv = self.generate_iv()
        
        # Pad command to AES block size (16 bytes) using PKCS#7
        block_size = 16
        padding_length = block_size - (len(command_apdu) % block_size)
        padded_command = command_apdu + bytes([padding_length] * padding_length)
        
        # Encrypt using AES-CBC
        cipher = Cipher(
            algorithms.AES(self.session_key), # type: ignore
            modes.CBC(iv),
            backend=default_backend()
        )
        encryptor = cipher.encryptor()
        encrypted_data = encryptor.update(padded_command) + encryptor.finalize()
        
        # Prepare data for MAC calculation: IV + data_size + encrypted_data
        data_size = struct.pack('>H', len(encrypted_data))
        mac_data = iv + data_size + encrypted_data
        
        # Calculate HMAC-SHA1
        mac = hmac.new(self.mac_key, mac_data, hashlib.sha1).digest() # type: ignore
        
        # Build secure channel APDU
        mac_size = struct.pack('>H', len(mac))
        secure_data = iv + data_size + encrypted_data + mac_size + mac
        
        return secure_data

    def decrypt_response(self, encrypted_response):
        """Decrypt a response from the secure channel"""
        if not self.initialized:
            raise SatocashException("Secure channel not initialized", sw=None)
        
        if len(encrypted_response) < 18:  # IV + size + MAC size
            raise SatocashException("Secure channel response too short", sw=None)
        
        pos = 0
        
        # Extract IV
        iv = encrypted_response[pos:pos+self.SIZE_SC_IV]
        pos += self.SIZE_SC_IV
        
        # Extract encrypted data size
        data_size = struct.unpack('>H', encrypted_response[pos:pos+2])[0]
        pos += 2
        
        # Extract encrypted data
        encrypted_data = encrypted_response[pos:pos+data_size]
        pos += data_size
        
        # Decrypt using AES-CBC
        cipher = Cipher(
            algorithms.AES(self.session_key), # type: ignore
            modes.CBC(iv),
            backend=default_backend()
        )
        decryptor = cipher.decryptor()
        padded_data = decryptor.update(encrypted_data) + decryptor.finalize()
        
        # Remove PKCS#7 padding
        padding_length = padded_data[-1]
        decrypted_data = padded_data[:-padding_length]
        
        return decrypted_data

class SatocashCard:
    def __init__(self, reader_name=None, verbose=False):
        """Initialize connection to the smart card"""
        self.verbose = verbose
        self.reader_name = reader_name
        self.connection = None
        self.cardservice = None # Store the CardService object

        self.secure_channel = SecureChannel()
        self.secure_channel_active = False
        
        # Common JavaCard applet AIDs to try
        self.COMMON_AIDS = [
            # Standard Satocash AID (adjust as needed)
            [0xA0, 0x00, 0x00, 0x00, 0x04, 0x53, 0x61, 0x74, 0x6F, 0x63, 0x61, 0x73, 0x68],
            # Alternative shorter AID
            [0x53, 0x61, 0x74, 0x6F, 0x63, 0x61, 0x73, 0x68],
            # Generic test AID
            [0xA0, 0x00, 0x00, 0x00, 0x62, 0x03, 0x01, 0x08, 0x01],
            # Another common pattern
            [0xA0, 0x00, 0x00, 0x01, 0x51, 0x00, 0x00, 0x00],
        ]
        
        # Command constants from the applet  
        self.CLA_BITCOIN = 0xB0
        self.INS_SETUP = 0x2A
        self.INS_SATOCASH_GET_STATUS = 0xB0
        self.INS_GET_STATUS = 0x3C
        self.INS_INIT_SECURE_CHANNEL = 0x81
        self.INS_PROCESS_SECURE_CHANNEL = 0x82
        self.INS_VERIFY_PIN = 0x42
        self.INS_CHANGE_PIN = 0x44
        self.INS_UNBLOCK_PIN = 0x46
        self.INS_LOGOUT_ALL = 0x60
        
        # Satocash specific instructions
        self.INS_SATOCASH_IMPORT_MINT = 0xB1
        self.INS_SATOCASH_EXPORT_MINT = 0xB2
        self.INS_SATOCASH_REMOVE_MINT = 0xB3
        self.INS_SATOCASH_IMPORT_KEYSET = 0xB4
        self.INS_SATOCASH_EXPORT_KEYSET = 0xB5
        self.INS_SATOCASH_REMOVE_KEYSET = 0xB6
        self.INS_SATOCASH_IMPORT_PROOF = 0xB7
        self.INS_SATOCASH_EXPORT_PROOFS = 0xB8
        self.INS_SATOCASH_GET_PROOF_INFO = 0xB9
        
        # Configuration instructions
        self.INS_CARD_LABEL = 0x3D
        self.INS_SET_NDEF = 0x3F
        self.INS_SET_NFC_POLICY = 0x3E
        self.INS_SET_PIN_POLICY = 0x3A
        self.INS_SET_PINLESS_AMOUNT = 0x3B
        self.INS_BIP32_GET_AUTHENTIKEY = 0x73
        self.INS_EXPORT_AUTHENTIKEY = 0xAD
        self.INS_PRINT_LOGS = 0xA9
        
        # PKI instructions
        self.INS_EXPORT_PKI_PUBKEY = 0x98
        self.INS_IMPORT_PKI_CERTIFICATE = 0x92
        self.INS_EXPORT_PKI_CERTIFICATE = 0x93
        self.INS_SIGN_PKI_CSR = 0x94
        self.INS_LOCK_PKI = 0x99
        self.INS_CHALLENGE_RESPONSE_PKI = 0x9A
        
        self.selected_aid = None
        self.authenticated = False

    def wait_for_card(self, timeout=None):
        """
        Waits for a smart card to be inserted or come into contact with the reader.
        Returns True if a card is detected and connected, False otherwise (on timeout).
        """
        if self.verbose:
            print("Waiting for card...")

        cardrequest = CardRequest(timeout=timeout)

        try:
            self.cardservice = cardrequest.waitforcard()
            if self.cardservice:
                self.connection = self.cardservice.connection
                self.connection.connect()
                if self.verbose:
                    print(f"Card detected and connected via reader: {self.cardservice}")
                
                # Immediately try to get ATR to confirm connection stability
                try:
                    atr = self.connection.getATR()
                    if self.verbose:
                        print(f"Confirmed connection with ATR: {toHexString(atr)}")
                    return True
                except CardConnectionException as e:
                    if self.verbose:
                        print(f"Connection confirmed but ATR failed immediately: {e}")
                    self.connection = None # Invalidate connection
                    self.cardservice = None
                    return False
            else:
                if self.verbose:
                    print("No card detected within timeout.")
                return False
        except Exception as e:
            if self.verbose:
                print(f"Error waiting for card: {e}")
            raise SatocashException(f"Error waiting for card: {e}", sw=0x6F00)

    def send_apdu(self, cla, ins, p1=0, p2=0, data=None, le=None, silent=False, retry=True):
        """Send APDU command to the card"""
        if not self.connection:
            raise SatocashException("No card connected. Call wait_for_card() first.", sw=0x6F00)

        apdu = [cla, ins, p1, p2]
        
        if data:
            apdu.append(len(data))
            apdu.extend(data)
        
        if le is not None:
            apdu.append(le)
        
        if self.verbose and not silent:
            print(f"Sending APDU: {toHexString(apdu)}")
        
        try:
            response, sw1, sw2 = self.connection.transmit(apdu)
            sw = (sw1 << 8) | sw2
            
            if self.verbose and not silent:
                print(f"Response: {toHexString(response)} SW: {hex(sw)}")
            
            return response, sw
        except CardConnectionException as e:
            if self.verbose and not silent:
                print(f"APDU transmission error (CardConnectionException): {e}")
            if retry and self.cardservice:
                if self.verbose:
                    print("Attempting to reconnect and retry APDU...")
                try:
                    self.connection.disconnect()
                    self.connection = self.cardservice.connection
                    self.connection.connect()
                    if self.verbose:
                        print("Reconnection successful. Retrying APDU.")
                    return self.send_apdu(cla, ins, p1, p2, data, le, silent, retry=False) # Only one retry
                except Exception as reconnect_e:
                    if self.verbose:
                        print(f"Reconnection failed: {reconnect_e}")
                    raise SatocashException(f"APDU transmission error and reconnection failed: {reconnect_e}", sw=0x6F00)
            else:
                raise SatocashException(f"APDU transmission error: {e}", sw=0x6F00)
        except Exception as e:
            if self.verbose and not silent:
                print(f"APDU transmission error: {e}")
            raise SatocashException(f"APDU transmission error: {e}", sw=0x6F00)

    def send_secure_apdu(self, cla, ins, p1=0, p2=0, data=None):
        """Send an APDU through the secure channel"""
        if not self.secure_channel_active:
            raise SatocashException("Secure channel not initialized", sw=Error.SECURE_CHANNEL_UNINITIALIZED)
        
        # Build the original APDU
        apdu = [cla, ins, p1, p2]
        if data:
            apdu.append(len(data))
            apdu.extend(data)
        
        # Encrypt the APDU
        encrypted_data = self.secure_channel.encrypt_command(bytes(apdu))
        
        # Send through ProcessSecureChannel
        response, sw = self.send_apdu(
            self.CLA_BITCOIN,
            self.INS_PROCESS_SECURE_CHANNEL,
            0, 0,
            list(encrypted_data),
            silent=not self.verbose # Pass self.verbose to silent
        )
        
        if sw != Error.SUCCESS:
            raise SatocashException("Secure channel APDU transmission failed", sw=sw)
        
        # Decrypt the response
        if response:
            decrypted_response = self.secure_channel.decrypt_response(bytes(response))
            return list(decrypted_response), Error.SUCCESS
        else:
            return [], Error.SUCCESS

    def get_card_atr(self):
        """Get card ATR"""
        if not self.connection:
            raise SatocashException("No card connected. Call wait_for_card() first.", sw=0x6F00)
        try:
            atr = self.connection.getATR()
            if self.verbose:
                print(f"Card ATR: {toHexString(atr)}")
            return atr
        except CardConnectionException as e:
            if self.verbose:
                print(f"Failed to get ATR: {e}")
            raise SatocashException(f"Failed to get ATR: {e}", sw=0x6F00)


    def discover_applets(self):
        """Try to discover available applets"""
        if self.verbose:
            print("\n=== Discovering Applets ===")
        
        # First get card ATR
        # This call is now more robust due to changes in wait_for_card
        self.get_card_atr() 
        
        # Try different AIDs
        for i, aid in enumerate(self.COMMON_AIDS):
            if self.verbose:
                print(f"\nTrying AID {i+1}: {toHexString(aid)}")
            try:
                response, sw = self.send_apdu(0x00, 0xA4, 0x04, 0x00, aid, silent=not self.verbose)
                
                if sw == Error.SUCCESS:
                    if self.verbose:
                        print(f"✓ Successfully selected AID: {toHexString(aid)}")
                    self.selected_aid = aid
                    
                    # Try to get status with this AID
                    if self.verbose:
                        print("Testing Satocash status command...")
                    response, sw = self.send_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_GET_STATUS, silent=not self.verbose)
                    
                    if sw == Error.SUCCESS:
                        if self.verbose:
                            print("✓ Satocash applet detected!")
                        return aid
                    else:
                        # Try general status
                        response, sw = self.send_apdu(self.CLA_BITCOIN, self.INS_GET_STATUS, silent=not self.verbose)
                        if sw == Error.SUCCESS:
                            if self.verbose:
                                print("✓ Compatible applet detected (general status works)")
                            return aid
                        else:
                            if self.verbose:
                                print(f"Selected but status failed: SW={hex(sw)}")
                else:
                    if self.verbose:
                        print(f"✗ Selection failed: SW={hex(sw)}")
            except SatocashException as e:
                if self.verbose:
                    print(f"✗ APDU transmission error during AID discovery: {e}")
        
        # Try to list applets using card manager
        if self.verbose:
            print("\n=== Trying Card Manager ===")
        self.try_card_manager_list()
        
        return None

    def try_card_manager_list(self):
        """Try to list applets using card manager commands"""
        # Try to select card manager
        cm_aids = [
            [0xA0, 0x00, 0x00, 0x01, 0x51, 0x00, 0x00],  # GP Card Manager
            [0xA0, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00],  # Another common CM AID
        ]
        
        for cm_aid in cm_aids:
            if self.verbose:
                print(f"Trying Card Manager AID: {toHexString(cm_aid)}")
            try:
                response, sw = self.send_apdu(0x00, 0xA4, 0x04, 0x00, cm_aid, silent=not self.verbose)
                
                if sw == Error.SUCCESS:
                    if self.verbose:
                        print("✓ Card Manager selected")
                    # Try to get status
                    response, sw = self.send_apdu(0x80, 0xF2, 0x40, 0x00, [0x4F, 0x00], le=0x00, silent=not self.verbose)
                    if sw == Error.SUCCESS:
                        if self.verbose:
                            print(f"Applet list response: {toHexString(response)}")
                    break
            except SatocashException as e:
                if self.verbose:
                    print(f"✗ APDU transmission error during Card Manager attempt: {e}")

    def select_applet(self, aid=None):
        """Select the Satocash applet"""
        if aid is None:
            aid = self.discover_applets()
            if aid is None:
                raise SatocashException("No compatible applet found", sw=None)
        
        if self.verbose:
            print(f"\nSelecting applet with AID: {toHexString(aid)}")
        response, sw = self.send_apdu(0x00, 0xA4, 0x04, 0x00, aid, silent=not self.verbose)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ Applet selected successfully")
            self.selected_aid = aid
            return response
        else:
            raise SatocashException("Failed to select applet", sw=sw)

    def get_status(self, use_secure_channel=False):
        """Get status (try both Satocash-specific and general)"""
        if self.verbose:
            print("\n=== Getting Status ===")

        # Try Satocash-specific status first
        if self.verbose:
            print("Trying Satocash status...")
        try:
            response, sw = self.send_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_GET_STATUS, silent=not self.verbose)
            
            if sw == Error.SUCCESS:
                if self.verbose:
                    print("✓ Satocash status successful")
                return self._parse_satocash_status(response)
        except SatocashException as e:
            if self.verbose:
                print(f"Satocash status command failed: {e}")
        
        # Try general status
        if self.verbose:
            print("Trying general status...")
        try:
            response, sw = self.send_apdu(self.CLA_BITCOIN, self.INS_GET_STATUS, silent=not self.verbose)
            
            if sw == Error.SUCCESS:
                if self.verbose:
                    print("✓ General status successful")
                return self._parse_general_status(response)
        except SatocashException as e:
            if self.verbose:
                print(f"General status command failed: {e}")
        
        raise SatocashException("Both status commands failed", sw=sw)

    def _parse_satocash_status(self, status_data):
        """Parse Satocash status response"""
        if len(status_data) < 22:  # Minimum expected Satocash status length
            raise SatocashException("Satocash status response too short", sw=None)
        
        pos = 0
        protocol_major = status_data[pos]; pos += 1
        protocol_minor = status_data[pos]; pos += 1
        applet_major = status_data[pos]; pos += 1
        applet_minor = status_data[pos]; pos += 1
        
        pin_tries = status_data[pos]; pos += 1
        puk_tries = status_data[pos]; pos += 1
        pin1_tries = status_data[pos]; pos += 1
        puk1_tries = status_data[pos]; pos += 1
        
        needs_2fa = status_data[pos]; pos += 1
        rfu = status_data[pos]; pos += 1
        setup_done = status_data[pos]; pos += 1
        needs_secure_channel = status_data[pos]; pos += 1
        nfc_policy = status_data[pos]; pos += 1
        pin_policy = status_data[pos]; pos += 1
        rfu2 = status_data[pos]; pos += 1
        
        max_mints = status_data[pos]; pos += 1
        nb_mints = status_data[pos]; pos += 1
        max_keysets = status_data[pos]; pos += 1
        nb_keysets = status_data[pos]; pos += 1
        
        if len(status_data) >= pos + 6:
            max_proofs = (status_data[pos] << 8) | status_data[pos+1]; pos += 2
            nb_proofs_unspent = (status_data[pos] << 8) | status_data[pos+1]; pos += 2
            nb_proofs_spent = (status_data[pos] << 8) | status_data[pos+1]; pos += 2
        else:
            max_proofs = nb_proofs_unspent = nb_proofs_spent = 0
        
        status_info = {
            "protocol_version": f"{protocol_major}.{protocol_minor}",
            "applet_version": f"{applet_major}.{applet_minor}",
            "pin_tries_remaining": pin_tries,
            "puk_tries_remaining": puk_tries,
            "pin1_tries_remaining": pin1_tries,
            "puk1_tries_remaining": puk1_tries,
            "needs_2fa": bool(needs_2fa),
            "setup_done": bool(setup_done),
            "needs_secure_channel": bool(needs_secure_channel),
            "nfc_policy": nfc_policy,
            "pin_policy": pin_policy,
            "max_mints": max_mints,
            "nb_mints": nb_mints,
            "max_keysets": max_keysets,
            "nb_keysets": nb_keysets,
            "max_proofs": max_proofs,
            "nb_proofs_unspent": nb_proofs_unspent,
            "nb_proofs_spent": nb_proofs_spent,
        }

        if self.verbose:
            print(f"Protocol version: {status_info['protocol_version']}")
            print(f"Applet version: {status_info['applet_version']}")
            print(f"PIN tries remaining: {status_info['pin_tries_remaining']}")
            print(f"PUK tries remaining: {status_info['puk_tries_remaining']}")
            print(f"Setup done: {status_info['setup_done']}")
            print(f"Needs secure channel: {status_info['needs_secure_channel']}")
            print(f"Max mints: {status_info['max_mints']}, Used: {status_info['nb_mints']}")
            print(f"Max keysets: {status_info['max_keysets']}, Used: {status_info['nb_keysets']}")
            print(f"Max proofs: {status_info['max_proofs']}, Unspent: {status_info['nb_proofs_unspent']}, Spent: {status_info['nb_proofs_spent']}")
        
        return status_info

    def _parse_general_status(self, status_data):
        """Parse general status response"""
        if len(status_data) < 9:
            raise SatocashException("General status response too short", sw=None)

        pos = 0
        protocol_major = status_data[pos]; pos += 1
        protocol_minor = status_data[pos]; pos += 1
        applet_major = status_data[pos]; pos += 1
        applet_minor = status_data[pos]; pos += 1
        
        pin_tries = status_data[pos]; pos += 1
        puk_tries = status_data[pos]; pos += 1
        pin1_tries = status_data[pos]; pos += 1
        puk1_tries = status_data[pos]; pos += 1
        
        needs_2fa = status_data[pos]; pos += 1
        
        status_info = {
            "protocol_version": f"{protocol_major}.{protocol_minor}",
            "applet_version": f"{applet_major}.{applet_minor}",
            "pin_tries_remaining": pin_tries,
            "puk_tries_remaining": puk_tries,
            "pin1_tries_remaining": pin1_tries,
            "puk1_tries_remaining": puk1_tries,
            "needs_2fa": bool(needs_2fa),
        }

        if self.verbose:
            print(f"Protocol version: {status_info['protocol_version']}")
            print(f"Applet version: {status_info['applet_version']}")
            print(f"PIN tries remaining: {status_info['pin_tries_remaining']}")
            print(f"PUK tries remaining: {status_info['puk_tries_remaining']}")
        
        return status_info

    def setup_applet(self, default_pin="0", user_pin="1234", user_puk="12345678", 
                    pin_tries=3, puk_tries=5):
        """Setup the applet with PIN configuration"""
        if self.verbose:
            print("\n=== Setting up applet ===")
        
        # Prepare setup data according to the applet specification
        setup_data = []
        
        # Default PIN
        default_pin_bytes = default_pin.encode('ascii')[:16]
        setup_data.append(len(default_pin_bytes))
        setup_data.extend(default_pin_bytes)
        
        # PIN0 configuration
        setup_data.append(pin_tries)  # PIN tries
        setup_data.append(puk_tries)  # PUK tries
        
        user_pin_bytes = user_pin.encode('ascii')[:16]
        setup_data.append(len(user_pin_bytes))
        setup_data.extend(user_pin_bytes)
        
        user_puk_bytes = user_puk.encode('ascii')[:16]
        setup_data.append(len(user_puk_bytes))
        setup_data.extend(user_puk_bytes)
        
        # PIN1 configuration (unused)
        setup_data.extend([pin_tries, puk_tries, 0, 0])
        
        # RFU (7 bytes) + option flags (2 bytes)
        setup_data.extend([0] * 9)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SETUP, 0, 0, setup_data)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ Setup completed successfully!")
            return True
        elif sw == Error.SETUP_ALREADY_DONE:
            if self.verbose:
                print("✗ Setup already done")
            raise SatocashException("Setup already done", sw=sw)
        else:
            raise SatocashException("Setup failed", sw=sw)

    def verify_pin(self, pin, pin_id=0):
        """Verify PIN"""
        if self.verbose:
            print(f"\n=== Verifying PIN ID {pin_id} ===")
        
        pin_bytes = pin.encode('ascii')
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_VERIFY_PIN, 
                                    pin_id, 0, list(pin_bytes))
        
        if sw == Error.SUCCESS:
            self.authenticated = True
            if self.verbose:
                print("✓ PIN verified successfully!")
            return True
        elif (sw & 0xFFF0) == Error.PIN_FAILED:
            remaining_tries = sw & 0x000F
            if self.verbose:
                print(f"✗ PIN verification failed. Remaining tries: {remaining_tries}")
            raise SatocashException(f"PIN verification failed. Remaining tries: {remaining_tries}", sw=sw)
        else:
            raise SatocashException("PIN verification failed", sw=sw)

    def change_pin(self, old_pin, new_pin, pin_id=0):
        """Change PIN"""
        if self.verbose:
            print(f"\n=== Changing PIN ID {pin_id} ===")
        
        old_pin_bytes = old_pin.encode('ascii')
        new_pin_bytes = new_pin.encode('ascii')
        
        data = [len(old_pin_bytes)] + list(old_pin_bytes) + [len(new_pin_bytes)] + list(new_pin_bytes)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_CHANGE_PIN, 
                                    pin_id, 0, data)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ PIN changed successfully!")
            return True
        else:
            raise SatocashException("PIN change failed", sw=sw)

    def unblock_pin(self, puk, pin_id=0):
        """Unblock PIN using PUK"""
        if self.verbose:
            print(f"\n=== Unblocking PIN ID {pin_id} ===")
        
        puk_bytes = puk.encode('ascii')
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_UNBLOCK_PIN, 
                                    pin_id, 0, list(puk_bytes))
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ PIN unblocked successfully!")
            return True
        else:
            raise SatocashException("PIN unblock failed", sw=sw)

    def logout_all(self):
        """Logout all authenticated identities"""
        if self.verbose:
            print("\n=== Logging out all identities ===")
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_LOGOUT_ALL)
        
        if sw == Error.SUCCESS:
            self.authenticated = False
            if self.verbose:
                print("✓ Logged out successfully!")
            return True
        else:
            raise SatocashException("Logout failed", sw=sw)

    # Satocash Mint Methods
    def import_mint(self, mint_url):
        """Import a mint URL"""
        if self.verbose:
            print(f"\n=== Importing mint: {mint_url} ===")
        
        mint_url_bytes = mint_url.encode('ascii')
        data = [len(mint_url_bytes)] + list(mint_url_bytes)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_IMPORT_MINT, 
                                    0, 0, data)
        
        if sw == Error.SUCCESS and response:
            mint_index = response[0]
            if self.verbose:
                print(f"✓ Mint imported successfully at index: {mint_index}")
            return mint_index
        else:
            raise SatocashException("Mint import failed", sw=sw)

    def export_mint(self, mint_index):
        """Export a mint URL by index"""
        if self.verbose:
            print(f"\n=== Exporting mint at index {mint_index} ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_EXPORT_MINT, 
                                    mint_index, 0)
        
        if sw == Error.SUCCESS and response:
            url_size = response[0]
            if url_size > 0 and len(response) > 1:
                mint_url = bytes(response[1:url_size+1]).decode('ascii')
                if self.verbose:
                    print(f"✓ Mint URL: {mint_url}")
                return mint_url
            else:
                if self.verbose:
                    print("✓ Empty mint slot")
                return None
        else:
            raise SatocashException("Mint export failed", sw=sw)

    def remove_mint(self, mint_index):
        """Remove a mint by index"""
        if self.verbose:
            print(f"\n=== Removing mint at index {mint_index} ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_REMOVE_MINT, 
                                    mint_index, 0)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ Mint removed successfully!")
            return True
        else:
            raise SatocashException("Mint removal failed", sw=sw)

    # Satocash Keyset Methods
    def import_keyset(self, keyset_id, mint_index, unit: Unit):
        """Import a keyset"""
        if self.verbose:
            print("\n=== Importing keyset ===")
            print(f"Keyset ID: {keyset_id}")
            print(f"Mint index: {mint_index}")
            print(f"Unit: {unit}")
        
        data = bytes.fromhex(keyset_id) + bytes([mint_index, unit.value])
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_IMPORT_KEYSET, 
                                    0, 0, data)
        
        if sw == Error.SUCCESS and response:
            keyset_index = response[0]
            if self.verbose:
                print(f"✓ Keyset imported successfully at index: {keyset_index}")
            return keyset_index
        else:
            raise SatocashException("Keyset import failed", sw=sw)

    def export_keysets(self, keyset_indices):
        """Export keysets by indices"""
        if self.verbose:
            print(f"\n=== Exporting keysets {keyset_indices} ===")
        
        data = bytes([len(keyset_indices)] + keyset_indices)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_EXPORT_KEYSET, 
                                    0, 0, data)
        
        if sw == Error.SUCCESS and response:
            if self.verbose:
                print("✓ Keysets exported successfully")
            keysets = []
            pos = 0
            while pos < len(response):
                if pos + 11 <= len(response):
                    keyset_index = response[pos]
                    keyset_id = bytes(response[pos+1:pos+9])
                    mint_index = response[pos+9]
                    unit = response[pos+10]
                    keysets.append({
                        'index': keyset_index,
                        'id': keyset_id,
                        'mint_index': mint_index,
                        'unit': unit
                    })
                    pos += 11
                else:
                    break
            
            if self.verbose:
                for keyset in keysets:
                    print(f"  Index: {keyset['index']}, ID: {keyset['id'].hex()}, "
                        f"Mint: {keyset['mint_index']}, Unit: {keyset['unit']}")
            
            return keysets
        else:
            raise SatocashException("Keyset export failed", sw=sw)

    def remove_keyset(self, keyset_index):
        """Remove a keyset by index"""
        if self.verbose:
            print(f"\n=== Removing keyset at index {keyset_index} ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_REMOVE_KEYSET, 
                                    keyset_index, 0)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ Keyset removed successfully!")
            return True
        else:
            raise SatocashException("Keyset removal failed", sw=sw)

    # Satocash Proof Methods
    def import_proof(self, keyset_index, amount_exponent, unblinded_key, secret):
        """Import a proof"""
        if self.verbose:
            print("\n=== Importing proof ===")
            print(f"Keyset index: {keyset_index}")
            print(f"Amount exponent: {amount_exponent}")
        
        data = bytes([keyset_index, amount_exponent]) + bytes.fromhex(unblinded_key) + bytes.fromhex(secret)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_IMPORT_PROOF, 
                                    0, 0, data)
        
        if sw == Error.SUCCESS and len(response) >= 2:
            proof_index = (response[0] << 8) | response[1]
            if self.verbose:
                print(f"✓ Proof imported successfully at index: {proof_index}")
            return proof_index
        else:
            raise SatocashException("Proof import failed", sw=sw)

    def export_proofs(self, proof_indices):
        """Export proofs by indices (multi-step process)"""
        if self.verbose:
            print(f"\n=== Exporting proofs {proof_indices} ===")
        
        # Step 1: Initialize
        proof_indices_bytes = []
        for idx in proof_indices:
            proof_indices_bytes.extend(struct.pack(">H", idx))
        
        data = bytes([len(proof_indices)] + proof_indices_bytes)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_EXPORT_PROOFS, 
                                    0, MultiApduOperations.OP_INIT, data)
        
        if sw != Error.SUCCESS:
            raise SatocashException("Proof export initialization failed", sw=sw)
        
        # Parse initial response if any
        all_proofs = []
        if response:
            all_proofs.extend(self._parse_proof_response(response))
        
        # Step 2: Process remaining proofs
        while len(all_proofs) < len(proof_indices):
            response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_EXPORT_PROOFS, 
                                        0, MultiApduOperations.OP_PROCESS)
            
            if sw != Error.SUCCESS:
                break # No more data or error
            
            if not response:
                break # No more data
                
            proofs = self._parse_proof_response(response)
            if not proofs:
                break # No more data
                
            all_proofs.extend(proofs)
        
        if self.verbose:
            print(f"✓ Exported {len(all_proofs)} proofs")
            for proof in all_proofs:
                print(f"  Index: {proof['index']}, State: {proof['state']}, "
                    f"Keyset: {proof['keyset_index']}, Amount exp: {proof['amount_exponent']}"
                    f"Unblinded Key: {proof['unblinded_key']}, Secret: {proof['secret']}")
        
        return all_proofs

    def _parse_proof_response(self, response):
        """Parse proof export response"""
        proofs = []
        pos = 0
        
        while pos + 70 <= len(response):  # 2 + 68 bytes per proof
            proof_index = (response[pos] << 8) | response[pos+1]
            pos += 2
            
            state = response[pos]
            keyset_index = response[pos+1]
            amount_exponent = response[pos+2]
            unblinded_key = bytes(response[pos+3:pos+36])
            secret = bytes(response[pos+36:pos+68])
            pos += 68
            
            proofs.append({
                'index': proof_index,
                'state': state,
                'keyset_index': keyset_index,
                'amount_exponent': amount_exponent,
                'unblinded_key': unblinded_key,
                'secret': secret
            })
        
        return proofs

    def get_proof_info(self, unit: Unit, info_type: ProofInfoType, index_start=0, index_size=MAX_NB_PROOFS):
        """Get proof metadata"""
        if self.verbose:
            print("\n=== Getting proof info ===")
            print(f"Unit: {str(unit)}, Info type: {str(info_type)}")
        
        data = struct.pack(">H", index_start) + struct.pack(">H", index_size)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SATOCASH_GET_PROOF_INFO, 
                                    unit.value, info_type.value, data)
        
        if sw == Error.SUCCESS and response:
            if self.verbose:
                print(f"✓ Got proof info: {len(response)} entries")
                print(f"Data: {toHexString(response)}")
            return response
        else:
            raise SatocashException("Get proof info failed", sw=sw)

    def set_card_label(self, label):
        """Set card label"""
        if self.verbose:
            print(f"\n=== Setting card label: {label} ===")
        
        label_bytes = label.encode('ascii')
        data = [len(label_bytes)] + list(label_bytes)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_CARD_LABEL, 
                                    0, 0, data)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ Card label set successfully!")
            return True
        else:
            raise SatocashException("Set card label failed", sw=sw)

    def get_card_label(self):
        """Get card label"""
        if self.verbose:
            print("\n=== Getting card label ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_CARD_LABEL, 
                                    0, 1)
        
        if sw == Error.SUCCESS and response:
            label_size = response[0]
            if label_size > 0 and len(response) > 1:
                label = bytes(response[1:label_size+1]).decode('ascii') # Corrected slice
                if self.verbose:
                    print(f"✓ Card label: {label}")
                return label
            else:
                if self.verbose:
                    print("✓ No card label set")
                return ""
        else:
            raise SatocashException("Get card label failed", sw=sw)

    def set_nfc_policy(self, policy):
        """Set NFC policy (0=enabled, 1=disabled, 2=blocked)"""
        if self.verbose:
            print(f"\n=== Setting NFC policy: {policy} ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SET_NFC_POLICY, 
                                    policy, 0)
        
        if sw == Error.SUCCESS:
            policy_names = {0: "enabled", 1: "disabled", 2: "blocked"}
            if self.verbose:
                print(f"✓ NFC policy set to: {policy_names.get(policy, 'unknown')}")
            return True
        else:
            raise SatocashException("Set NFC policy failed", sw=sw)

    def set_pin_policy(self, policy):
        """Set PIN policy mask"""
        if self.verbose:
            print(f"\n=== Setting PIN policy: {policy} ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SET_PIN_POLICY, 
                                    policy, 0)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ PIN policy set successfully!")
                if policy & 0x01:
                    print("  - PIN required for info operations")
                if policy & 0x02:
                    print("  - PIN required for state changes")
                if policy & 0x04:
                    print("  - PIN required for payments")
            return True
        else:
            raise SatocashException("Set PIN policy failed", sw=sw)

    def set_pinless_amount(self, unit: Unit, amount: int):
        """Set maximum amount for PIN-less transactions.
        
        This function sets the max amount that can be spent without asking for PIN.
        Once this amount has been spent, a PIN is required for any additional payment.
        Amount limit can then be reset again.
        If amount is [0xff, 0xff, 0xff, 0xff], then PIN is never required.
        PIN must be validated to call this function.

        Args:
            unit (Unit): Unit for the amount (SAT, MSAT, USD, EUR)
            amount (int): Amount value (will be converted to 4 bytes)

        Raises:
            SatocashException: If operation fails or if unit is invalid
        """
        if self.verbose:
            print(f"\n=== Setting PIN-less amount: {amount} {unit.name} ===")
        
        # Check unit validity
        if unit == Unit.EMPTY:
            raise SatocashException("Invalid unit for PIN-less amount", sw=Error.INCORRECT_P1)
            
        # Convert amount to 4 bytes, big-endian
        amount_bytes = [(amount >> 24) & 0xFF, (amount >> 16) & 0xFF, 
                       (amount >> 8) & 0xFF, amount & 0xFF]
        
        # Send command with unit in P1 parameter
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SET_PINLESS_AMOUNT, 
                                    unit.value, 0, amount_bytes)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ PIN-less amount set successfully!")
            return True
        elif sw == Error.UNAUTHORIZED:
            raise SatocashException("PIN not validated", sw=sw)
        elif sw == Error.INCORRECT_P1:
            raise SatocashException("Invalid unit specified", sw=sw)
        else:
            raise SatocashException("Set PIN-less amount failed", sw=sw)

    def export_authentikey(self):
        """Export authentication key"""
        if self.verbose:
            print("\n=== Exporting authentikey ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_EXPORT_AUTHENTIKEY)
        
        if sw == Error.SUCCESS and response:
            if self.verbose:
                print("✓ Authentikey exported successfully!")
            if len(response) >= 4:
                coordx_size = (response[0] << 8) | response[1]
                if self.verbose:
                    print(f"Coordinate X size: {coordx_size}")
                if len(response) >= 2 + coordx_size + 2:
                    coordx = bytes(response[2:2+coordx_size])
                    sig_size = (response[2+coordx_size] << 8) | response[2+coordx_size+1]
                    if self.verbose:
                        print(f"Signature size: {sig_size}")
                    if len(response) >= 2 + coordx_size + 2 + sig_size:
                        signature = bytes(response[2+coordx_size+2:2+coordx_size+2+sig_size])
                        if self.verbose:
                            print(f"Coordinate X: {coordx.hex()}")
                            print(f"Signature: {signature.hex()}")
                        return {'coordx': coordx, 'signature': signature}
            raise SatocashException(f"Unexpected authentikey response format: {toHexString(response)}", sw=sw)
        else:
            raise SatocashException("Export authentikey failed", sw=sw)

    def init_secure_channel(self):
        """Initialize secure channel with proper ECDH key exchange"""
        if self.verbose:
            print("\n=== Initializing Secure Channel ===")

        # Generate client keypair
        client_pubkey_bytes = self.secure_channel.generate_client_keypair()
        if self.verbose:
            print(f"Generated client public key: {client_pubkey_bytes.hex()}")

        # Send InitiateSecureChannel command to card
        response, sw = self.send_apdu(
            self.CLA_BITCOIN, 
            self.INS_INIT_SECURE_CHANNEL,
            0, 0,
            list(client_pubkey_bytes),
            silent=not self.verbose # Pass self.verbose to silent
        )

        if sw != Error.SUCCESS:
            raise SatocashException("Failed to initialize secure channel", sw=sw)

        # Complete the handshake
        self.secure_channel.complete_handshake(response)
        self.secure_channel_active = True

        if self.verbose:
            print("✓ Secure channel initialized successfully!")
        return True

    def print_logs(self):
        """Print operation logs"""
        if self.verbose:
            print("\n=== Getting operation logs ===")
        
        all_logs = []

        # Initialize
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_PRINT_LOGS, 
                                    0, MultiApduOperations.OP_INIT)
        
        if sw != Error.SUCCESS:
            raise SatocashException("Log initialization failed", sw=sw)
        
        if len(response) >= 4:
            total_logs = (response[0] << 8) | response[1]
            avail_logs = (response[2] << 8) | response[3]
            if self.verbose:
                print(f"Total logs: {total_logs}, Available: {avail_logs}")
            
            if len(response) > 4:
                parsed_logs = self._parse_log_entry(response[4:])
                if parsed_logs:
                    all_logs.extend(parsed_logs)
        
        # Get remaining logs
        while True:
            response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_PRINT_LOGS, 
                                        0, MultiApduOperations.OP_PROCESS)
            
            if sw != Error.SUCCESS or not response:
                break
            
            parsed_logs = self._parse_log_entry(response)
            if not parsed_logs:
                break
            all_logs.extend(parsed_logs)
        
        return all_logs

    def _parse_log_entry(self, log_data):
        """Parse a single log entry or multiple entries"""
        logs = []
        pos = 0
        while pos + 7 <= len(log_data):  # Each log entry is 7 bytes
            instruction = log_data[pos]
            param1 = (log_data[pos+1] << 8) | log_data[pos+2]
            param2 = (log_data[pos+3] << 8) | log_data[pos+4]
            status = (log_data[pos+5] << 8) | log_data[pos+6]
            
            log_entry = {
                "instruction": instruction,
                "param1": param1,
                "param2": param2,
                "status": status
            }
            logs.append(log_entry)
            if self.verbose:
                print(f"  INS: {hex(instruction)}, Param1: {param1}, Param2: {param2}, Status: {hex(status)}")
            pos += 7
        return logs

    # PKI Methods
    def export_pki_pubkey(self):
        """Export PKI public key"""
        if self.verbose:
            print("\n=== Exporting PKI public key ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_EXPORT_PKI_PUBKEY, silent=not self.verbose)
        
        if sw == Error.SUCCESS and response:
            if self.verbose:
                print("✓ PKI public key exported successfully!")
            if len(response) == 65 and response[0] == 0x04:
                if self.verbose:
                    print(f"Public key (uncompressed): {bytes(response).hex()}")
                return bytes(response)
            else:
                raise SatocashException(f"Unexpected public key format: {toHexString(response)}", sw=sw)
        else:
            raise SatocashException("Export PKI public key failed", sw=sw)

    def sign_pki_csr(self, hash_data):
        """Sign PKI certificate signing request"""
        if self.verbose:
            print("\n=== Signing PKI CSR ===")
        
        if len(hash_data) != 32:
            raise SatocashException("Hash data must be exactly 32 bytes", sw=Error.INVALID_PARAMETER)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_SIGN_PKI_CSR, 
                                    0, 0, list(hash_data), silent=not self.verbose)
        
        if sw == Error.SUCCESS and response:
            if self.verbose:
                print("✓ PKI CSR signed successfully!")
                print(f"Signature: {bytes(response).hex()}")
            return bytes(response)
        else:
            raise SatocashException("Sign PKI CSR failed", sw=sw)

    def challenge_response_pki(self, challenge):
        """Perform PKI challenge-response authentication"""
        if self.verbose:
            print("\n=== PKI Challenge-Response ===")
        
        if len(challenge) != 32:
            raise SatocashException("Challenge must be exactly 32 bytes", sw=Error.INVALID_PARAMETER)
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_CHALLENGE_RESPONSE_PKI, 
                                    0, 0, list(challenge), silent=not self.verbose)
        
        if sw == Error.SUCCESS and response:
            if self.verbose:
                print("✓ PKI challenge-response successful!")
            if len(response) >= 34:
                challenge2 = bytes(response[:32])
                sig_size = (response[32] << 8) | response[33]
                if len(response) >= 34 + sig_size:
                    signature = bytes(response[34:34+sig_size])
                    if self.verbose:
                        print(f"Device challenge: {challenge2.hex()}")
                        print(f"Signature: {signature.hex()}")
                    return {'challenge2': challenge2, 'signature': signature}
            raise SatocashException(f"Unexpected PKI challenge-response format: {toHexString(response)}", sw=sw)
        else:
            raise SatocashException("PKI challenge-response failed", sw=sw)

    def lock_pki(self):
        """Lock PKI configuration"""
        if self.verbose:
            print("\n=== Locking PKI ===")
        
        response, sw = self.send_secure_apdu(self.CLA_BITCOIN, self.INS_LOCK_PKI)
        
        if sw == Error.SUCCESS:
            if self.verbose:
                print("✓ PKI locked successfully!")
            return True
        else:
            raise SatocashException("Lock PKI failed", sw=sw)

def main():
    verbose = True
    card = None
    #try:
    card = SatocashCard(verbose=verbose)
    
    # Wait for card insertion
    if not card.wait_for_card(timeout=60): # Wait up to 60 seconds for a card
        print("No card detected. Exiting.")
        return

    # Discover and select applet
    print("\n--- Card Initialization ---")
    aid = card.discover_applets()
    if aid:
        card.select_applet(aid)
    else:
        print("No Satocash applet found. Exiting.")
        return

    # Initialize Secure Channel
    print("\n--- Secure Channel Setup ---")
    card.init_secure_channel()
    
    # Main loop for PoS-like interaction
    while True:
        print("\n--- Satocash PoS Menu ---")
        print("1. Get Card Status")
        print("2. Verify PIN")
        print("3. Logout All")
        print("4. Import Mint")
        print("5. Export Mint")
        print("6. Remove Mint")
        print("7. Import Keyset")
        print("8. Export Keysets")
        print("9. Remove Keyset")
        print("10. Import Proof")
        print("11. Export Proofs")
        print("12. Get Proof Info")
        print("13. Set Card Label")
        print("14. Get Card Label")
        print("15. Set NFC Policy")
        print("16. Set PIN Policy")
        print("17. Set PIN-less Amount")
        print("18. Export Authentikey")
        print("19. Print Logs")
        print("20. Export PKI Public Key")
        print("21. Sign PKI CSR (dummy hash)")
        print("22. PKI Challenge-Response (dummy challenge)")
        print("23. Lock PKI")
        print("24. Setup Card")
        print("0. Exit")
        
        choice = input("Enter choice: ")

        try:
            if choice == '1':
                status = card.get_status(use_secure_channel=card.secure_channel_active) # Use secure channel if active
                print("Current Card Status:")
                for k, v in status.items():
                    print(f"  {k}: {v}")
            elif choice == '2':
                pin = input("Enter PIN (e.g., 1234): ")
                card.verify_pin(pin)
            elif choice == '3':
                card.logout_all()
            elif choice == '4':
                mint_url = input("Enter Mint URL: ")
                card.import_mint(mint_url)
            elif choice == '5':
                mint_index = int(input("Enter Mint Index to export: "))
                card.export_mint(mint_index)
            elif choice == '6':
                mint_index = int(input("Enter Mint Index to remove: "))
                card.remove_mint(mint_index)
            elif choice == '7':
                keyset_id = input("Enter Keyset ID (hex): ")
                mint_index = int(input("Enter Mint Index: "))
                unit_val = int(input("Enter Unit (0=EMPTY, 1=SAT, 2=MSAT, 3=USD, 4=EUR): "))
                unit = Unit(unit_val)
                card.import_keyset(keyset_id, mint_index, unit)
            elif choice == '8':
                indices_str = input("Enter comma-separated Keyset Indices to export (e.g., 0,1): ")
                keyset_indices = [int(x.strip()) for x in indices_str.split(',') if x.strip()]
                card.export_keysets(keyset_indices)
            elif choice == '9':
                keyset_index = int(input("Enter Keyset Index to remove: "))
                card.remove_keyset(keyset_index)
            elif choice == '10':
                keyset_index = int(input("Enter Keyset Index: "))
                amount_exponent = int(input("Enter Amount Exponent: "))
                unblinded_key = input("Enter Unblinded Key (hex): ")
                secret = input("Enter Secret (hex): ")
                card.import_proof(keyset_index, amount_exponent, unblinded_key, secret)
            elif choice == '11':
                indices_str = input("Enter comma-separated Proof Indices to export (e.g., 0,1): ")
                proof_indices = [int(x.strip()) for x in indices_str.split(',') if x.strip()]
                card.export_proofs(proof_indices)
            elif choice == '12':
                unit_val = int(input("Enter Unit (0=EMPTY, 1=SAT, 2=MSAT, 3=USD, 4=EUR): "))
                info_type_val = int(input("Enter Info Type (0=STATE, 1=KEYSET_INDEX, etc.): "))
                unit = Unit(unit_val)
                info_type = ProofInfoType(info_type_val)
                start_idx = int(input("Enter start index (default 0): ") or "0")
                size_idx = int(input(f"Enter size (default {MAX_NB_PROOFS}): ") or str(MAX_NB_PROOFS))
                card.get_proof_info(unit, info_type, start_idx, size_idx)
            elif choice == '13':
                label = input("Enter new card label: ")
                card.set_card_label(label)
            elif choice == '14':
                card.get_card_label()
            elif choice == '15':
                policy = int(input("Enter NFC policy (0=enabled, 1=disabled, 2=blocked): "))
                card.set_nfc_policy(policy)
            elif choice == '16':
                policy = int(input("Enter PIN policy mask (e.g., 7 for all): "))
                card.set_pin_policy(policy)
            elif choice == '17':
                unit_val = int(input("Enter Unit (1=SAT, 2=MSAT, 3=USD, 4=EUR): "))
                unit = Unit(unit_val)
                amount = int(input("Enter PIN-less amount (0xFFFFFFFF for no PIN required): "))
                card.set_pinless_amount(unit, amount)
            elif choice == '18':
                card.export_authentikey()
            elif choice == '19':
                logs = card.print_logs()
                if not logs:
                    print("No logs available.")
            elif choice == '20':
                card.export_pki_pubkey()
            elif choice == '21':
                dummy_hash = os.urandom(32) # Replace with actual hash in real scenario
                print(f"Using dummy hash: {dummy_hash.hex()}")
                card.sign_pki_csr(dummy_hash)
            elif choice == '22':
                dummy_challenge = os.urandom(32) # Replace with actual challenge
                print(f"Using dummy challenge: {dummy_challenge.hex()}")
                card.challenge_response_pki(dummy_challenge)
            elif choice == '23':
                card.lock_pki()
            elif choice == '24':
                default_pin = input("Enter default PIN (default '0'): ")
                user_pin = input("Enter user PIN (default '1234'): ")
                user_puk = input("Enter user PUK (default '12345678'): ")
                pin_tries = int(input("Enter pin tries (default 3): ") or "3")
                puk_tries = int(input("Enter puk tries (default 5): ") or "5")
                card.setup_applet(default_pin or "0", user_pin or "1234", user_puk or "12345678", pin_tries, puk_tries)
            elif choice == '0':
                print("Exiting.")
                break
            else:
                print("Invalid choice. Please try again.")
        except SatocashException as e:
            print(f"Card Error: {e.args[0]} (SW: {hex(e.sw)})")
        except ValueError:
            print("Invalid input. Please enter a valid number or hex string.")
        except Exception as e:
            print(f"An unexpected error occurred: {e}")

    '''
    except SatocashException as e:
        print(f"Initialization Error: {e.args[0]} (SW: {hex(e.sw) if e.sw else 'N/A'})")
    except Exception as e:
        print(f"An unhandled error occurred during setup: {e}")
    '''

if __name__ == "__main__":
    main()
