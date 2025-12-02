# NFC NDEF Cashu Payment Protocol – Payer (Reader) Specification

This document formalizes the **actual NFC/NDEF protocol implemented in this repo** under
`app/src/main/java/com/electricdreams/numo/ndef/`.

It describes exactly what a **payer device** (NFC reader/writer) must do on the wire
(APDUs, payloads, chunking) to:

1. Read a **Cashu payment request** from a PoS terminal that emulates a **Type 4 NDEF tag**.
2. Write a **Cashu token** (or equivalent result) back to the PoS.

There are **no recommendations** here – this is a description of the behavior
implemented by the following classes:

- `NdefHostCardEmulationService`
- `NdefProcessor`
- `NdefApduHandler`
- `NdefUpdateBinaryHandler`
- `NdefStateManager`
- `NdefMessageBuilder`
- `NdefMessageParser`
- `NdefConstants`
- `CashuPaymentHelper`

All statements below are derived from that code.

---

## 1. Roles and High‑Level Flow

### 1.1 Roles

- **PoS terminal (this app)**
  - Runs `NdefHostCardEmulationService` (HCE) and emulates an **NFC Forum Type 4 NDEF tag**.
  - Exposes:
    - NDEF Tag Application AID: `D2 76 00 00 85 01 01`.
    - A **Capability Container (CC) file** (`E1 03`).
    - A **single NDEF file** (`E1 04`).
  - When a payment is active, the NDEF file contains a **Cashu payment request** as a
    single NDEF **Text** record.
  - After the payer writes back an NDEF message, the PoS extracts a **Cashu token** from
    the text/URI content and processes the payment.

- **Payer device**
  - Acts as **ISO‑DEP / NFC reader**.
  - Sends APDUs to the PoS tag.
  - Reads the payment request NDEF.
  - Writes an NDEF message containing a **Cashu token** or URL from which the token
    is extractable.

### 1.2 High‑Level Message Flow

1. Payer establishes ISO‑DEP connection to the PoS HCE service.
2. Payer **selects the NDEF Tag Application** via ISO 7816 AID SELECT.
3. Payer may **select and read the CC file** to learn file IDs and (advertised) limits.
4. Payer **selects the NDEF file** and **reads** the NDEF message containing the
   Cashu payment request.
5. Payer processes the Cashu payment request off‑tag.
6. Payer **writes a new NDEF message** (Text or URI record) containing a Cashu token
   (or URL with token) via one or more UPDATE BINARY APDUs.
7. PoS parses the written NDEF and, if a valid token is present, validates and redeems it.

---

## 2. Application and Files Exposed by the PoS Tag

### 2.1 NDEF Tag Application AID

Defined in `NdefConstants.NDEF_SELECT_AID` and `NdefHostCardEmulationService.AID_SELECT_APDU`:

```text
00 A4 04 00 07 D2 76 00 00 85 01 01 00
```

- CLA = `00`
- INS = `A4` (SELECT)
- P1 = `04` (select by AID)
- P2 = `00`
- Lc = `07`
- Data = `D2 76 00 00 85 01 01`
- Le = `00`

**Tag behavior** (`NdefProcessor.processCommandApdu` and `NdefHostCardEmulationService.isAidSelectCommand`):

- If the command matches `NDEF_SELECT_AID` **byte for byte**, `NdefProcessor` returns
  `0x9000`.
- If it does not match exactly but the first 13 bytes match the AID select APDU
  (`AID_SELECT_APDU` in `NdefHostCardEmulationService`), the outer service still
  returns `0x9000`.
- No other internal state depends on this beyond allowing further APDUs.

### 2.2 Capability Container (CC) File

Defined in `NdefConstants.CC_FILE`:

```text
Offset  Value (hex)   Meaning
------  ------------  --------------------------------------
0x00    00 0F         CCLEN = 15 bytes
0x02    20            Mapping version 2.0
0x03    00 3B         MLe = 0x003B = 59 bytes (max READ)
0x05    00 34         MLc = 0x0034 = 52 bytes (max UPDATE)
0x07    04            T = NDEF File Control TLV
0x08    06            L = 6 (length of this TLV)
0x09    E1 04         NDEF File ID
0x0B    70 FF         Max NDEF size = 0x70FF (28,671 bytes)
0x0D    00            Read access (unrestricted)
0x0E    00            Write access (unrestricted)
```

The CC file is exposed under **File ID `E1 03`**.

### 2.3 NDEF File

The NDEF file is referenced in the CC file as **File ID `E1 04`** and is treated by
this implementation as a standard **Type 4 NDEF file**:

```text
Byte 0-1 : NLEN (big‑endian 16‑bit length of NDEF message, not including NLEN bytes)
Byte 2.. : NDEF message bytes (one or more NDEF records)
```

The PoS **only ever writes** this file from its own side when it wants to expose a
payment request. The payer can read it and later write a new NDEF message back.

---

## 3. APDU Commands Implemented by the PoS Tag

Implementation references:
- `NdefProcessor`
- `NdefApduHandler`
- `NdefUpdateBinaryHandler`
- `NdefConstants`

### 3.1 SELECT FILE (by File ID)

A SELECT FILE command is recognized when the first 4 bytes equal
`NdefConstants.NDEF_SELECT_FILE_HEADER = 00 A4 00 0C` and the APDU has at least 7
bytes (CLA, INS, P1, P2, Lc=2, FileID[2]).

**Command format:**

```text
00 A4 00 0C 02 XX YY
```

Where `XX YY` is the 2‑byte file ID.

**Tag behavior** (`NdefApduHandler.handleSelectFile`):

- If `XX YY == E1 03` (CC file ID):
  - `selectedFile = CC_FILE` (constant 15‑byte array from `NdefConstants`).
  - Response: `90 00`.

- If `XX YY == E1 04` (NDEF file ID):
  - If **and only if** both:
    - `stateManager.isInWriteMode() == true`, and
    - `stateManager.getMessageToSend()` is a non‑empty string

    then:

    - `ndefMessage = NdefMessageBuilder.createNdefMessage(messageToSend)` is built
      (see section 4.1).
    - `selectedFile = ndefMessage`.
    - Callback `onMessageSent()` is invoked.
    - Response: `90 00`.

  - Otherwise (no active payment request or write mode disabled):
    - Response: `6A 82` (file not found / unsupported in this mode).

- For any other file ID:
  - Response: `6A 82`.

### 3.2 READ BINARY

A READ BINARY command is recognized when the first two bytes equal
`NdefConstants.NDEF_READ_BINARY_HEADER = 00 B0` and the APDU has at least 5 bytes.

**Command format:**

```text
00 B0 P1 P2 Le
```

- `offset = (P1 << 8) | P2`.
- `length = Le` (0 is treated as 256).

**Tag behavior** (`NdefApduHandler.handleReadBinary`):

- If `selectedFile == null` or APDU length < 5:
  - Response: `6A 82`.

- Else:
  - If `offset + length > selectedFile.length`:
    - Response: `6A 82`.

  - Otherwise:
    - Return `selectedFile[offset .. offset+length-1]` followed by `90 00`.

**Important – enforcement of MLe:**

- The CC file advertises `MLe = 0x003B = 59` bytes.
- The implementation **does not enforce** this limit.
- Any `Le` in the range 1..255 works as long as `offset + length <= selectedFile.length`.

### 3.3 UPDATE BINARY

An UPDATE BINARY command is recognized when the first two bytes equal
`NdefConstants.NDEF_UPDATE_BINARY_HEADER = 00 D6` and the APDU has at least 5 bytes.

**Command format:**

```text
00 D6 P1 P2 Lc [data...]
```

- `offset = (P1 << 8) | P2`.
- `dataLength = Lc`.
- `data = apdu[5 .. 5+Lc-1]`.

**Tag behavior** (`NdefUpdateBinaryHandler.handleUpdateBinary`):

1. If `selectedFile == null` or APDU length < `5 + Lc`:
   - Response: `6A 82`.

2. If `selectedFile` equals the static `CC_FILE` array (byte‑wise compare):
   - Writing to CC is forbidden.
   - Response: `6A 82`.

3. The data is written into an internal **receive buffer**:
   `byte[] ndefData = stateManager.getNdefData()` (size = 65,536 bytes).

   - If `offset + Lc > ndefData.length`:
     - Response: `6A 82`.

   - Otherwise:
     - Copy `data` into `ndefData` at `offset`.
     - Update `lastMessageActivityTime`.

4. If `offset == 0 && Lc >= 2`, the first two bytes are treated as an NDEF
   **length header** (NLEN). In this case `handleLengthHeaderUpdate` is called
   (see 5.2).

5. Otherwise, `checkForCompleteMessage(offset, Lc)` is called to see whether a full
   NDEF message has been received (see 5.3).

6. The handler **always** returns `90 00` for a syntactically valid write that fits
   in the buffer, regardless of whether the NDEF message later validates or the
   Cashu payment succeeds. All application‑level errors are handled above APDU layer.

**Enforcement of MLc:**

- The CC file advertises `MLc = 0x0034 = 52` bytes.
- The implementation **does not check MLc**.
- Any `Lc` value that keeps `offset + Lc` within the 65,536‑byte buffer is accepted.

---

## 4. Outgoing NDEF (Payment Request) from PoS to Payer

### 4.1 Structure of the NDEF Message

When `NdefHostCardEmulationService.setPaymentRequest(String paymentRequest, long amount)`
is called, the following happens:

- `stateManager.messageToSend = paymentRequest`.
- `stateManager.isInWriteMode = true`.
- `stateManager.processIncomingMessages = true`.

Later, when the payer sends `SELECT FILE E1 04`, the tag builds an NDEF message via
`NdefMessageBuilder.createNdefMessage(messageToSend)`.

`createNdefMessage(String message)` produces:

1. **Text payload**

   ```text
   languageCode = "en"    (ASCII)
   textBytes    = message  (UTF‑8)
   statusByte   = length(languageCode)  // 2 (UTF‑8 encoding implied by bit 7 = 0)

   payload = [ statusByte ][ 'e' ][ 'n' ][ textBytes... ]
   ```

2. **Record type**

   ```text
   type = "T" (ASCII 0x54)   // well‑known "Text" record
   ```

3. **Record header**

   - If `payload.length <= 255`, the **Short Record (SR)** form is used:

     ```text
     header[0] = 0xD1  // MB=1, ME=1, SR=1, TNF=1 (well‑known)
     header[1] = 0x01  // type length = 1
     header[2] = payload.length (1 byte)
     header[3] = 0x54  // 'T'
     header[4..] = payload bytes
     ```

   - If `payload.length > 255`, the **normal record** form is used:

     ```text
     header[0] = 0xC1  // MB=1, ME=1, SR=0, TNF=1 (well‑known)
     header[1] = 0x01  // type length = 1
     header[2..5] = payload length (4 bytes, big‑endian)
     header[6]     = 0x54  // 'T'
     header[7..]   = payload bytes
     ```

4. **Type 4 NDEF file framing**

   The full byte array returned by `createNdefMessage` is:

   ```text
   NLEN = recordHeader.length
   full[0] = (NLEN >> 8) & 0xFF
   full[1] = NLEN & 0xFF
   full[2..] = recordHeader
   ```

   This is exactly what is used as `selectedFile` for READ BINARY when the NDEF
   file is selected.

### 4.2 Semantic Content of `messageToSend`

`messageToSend` is the **encoded Cashu payment request string** produced by
`CashuPaymentHelper.createPaymentRequest(...)` or related helpers. It typically
starts with `creqA` and is opaque to NFC; it is simply treated as UTF‑8 text.

From the payer’s point of view, the **payment request NDEF** is:

- A Type 4 NDEF file containing:
  - `NLEN` (2 bytes), followed by
  - A single **Text record** with:
    - language code `"en"`.
    - payload text equal to the ASCII/UTF‑8 encoded Cashu payment request
      (e.g. `"creqA..."`).

---

## 5. Incoming NDEF (Cashu Token) from Payer to PoS

Incoming NDEF messages are handled by:

- `NdefUpdateBinaryHandler` (for aggregation and framing).
- `NdefMessageParser` (for parsing NDEF records).
- `CashuPaymentHelper` and `NdefHostCardEmulationService` (for Cashu‑specific logic).

### 5.1 Receive Buffer and Framing

- All UPDATE BINARY data bytes are written into a single buffer:

  ```java
  byte[] ndefData = new byte[NdefConstants.MAX_NDEF_DATA_SIZE]; // 65536
  ```

- The **first two bytes** (`ndefData[0]`, `ndefData[1]`) are interpreted as
  `NLEN` – the length of the NDEF message **excluding** the NLEN bytes.

- The **expected NDEF length** is stored as `expectedNdefLength` in
  `NdefStateManager`.

#### 5.1.1 How a Complete Message is Detected

A complete NDEF message is considered received and scheduled for processing when
any of the following conditions are met (all derived from
`NdefUpdateBinaryHandler.handleLengthHeaderUpdate` + `checkForCompleteMessage`):

1. **Header written with non‑zero NLEN and data already present**:

   - `offset == 0`, `Lc >= 2`,
   - `newLength = ((ndefData[0] & 0xFF) << 8) | (ndefData[1] & 0xFF)` is `> 0`,
   - `hasNonZeroData(ndefData, newLength)` returns true (any non‑zero byte
     between indices 2 and `newLength + 1`).
   - Result: `processMessageAndReset(ndefData)` is called.

### 4.3 Mint List Semantics in Payment Requests

The Cashu payment request string (`creqA…`) MAY contain a list of allowed mints
as part of its encoded payload. How this field is populated depends on the
merchant’s settings in the Numo app:

- **Setting: “Accept payments from unknown mints” ENABLED**  
  (internally: `MintManager.isSwapFromUnknownMintsEnabled() == true`)

  - The PoS **omits** the `mints` field entirely from the `PaymentRequest`.
  - Rationale: some paying wallets interpret an explicit `mints` list as a
    **strict requirement**, not a hint. Omitting the field allows any wallet to
    pay with tokens from mints it chooses, including mints that are not yet
    configured on the merchant’s device.
  - Enforcement still happens on the PoS side:
    - The PoS checks that the received ecash covers the **expected amount**.
    - If the token’s mint is not in the merchant’s configured list, and the
      swap feature is enabled, the PoS will attempt to **swap** the incoming
      ecash into the merchant’s configured Lightning mint.

- **Setting: “Accept payments from unknown mints” DISABLED**  
  (`MintManager.isSwapFromUnknownMintsEnabled() == false`)

  - The PoS **includes** the configured **allowed mints** in the
    `PaymentRequest.mints` field.
  - From the paying wallet’s perspective this can be treated as a strict
    allow‑list: only these mints are expected to be used when constructing the
    payment.

This behavior applies uniformly to:

- NDEF/HCE payment requests created via
  `CashuPaymentHelper.createPaymentRequest(…)` (used by
  `PaymentMethodHandler` and `PaymentRequestActivity`).
- Nostr‑transport payment requests created via
  `CashuPaymentHelper.createPaymentRequestWithNostr(…)` (used by
  `NostrPaymentHandler`).

In all cases, **omitting** the mints field does *not* weaken the PoS’s
validation logic; it only relaxes the *hint* given to the paying wallet about
which mints it should prefer.


2. **Header + full body present in a single UPDATE**:

   - `offset == 0`, `Lc >= 2`,
   - same `newLength > 0`,
   - and `offset + Lc >= newLength + 2` (i.e. this single UPDATE covers
     the entire `NLEN + body`).
   - Result: `processMessageAndReset(ndefData)` is called.

3. **Body written after header, using multiple UPDATEs**:

   - `expectedNdefLength > 0` has already been set by a previous header write.
   - A later UPDATE BINARY call with `(offset + Lc) >= expectedNdefLength + 2`.
   - Result: `processMessageAndReset(ndefData)` is called.

4. **Timeout on partial message with data present**:

   - `expectedNdefLength` may be ≥ 0.
   - `hasNonZeroData(ndefData, expectedNdefLength)` returns true.
   - No UPDATE BINARY is received for longer than `MESSAGE_TIMEOUT_MS = 3000 ms`.
   - Result: timeout path in `handlePartialMessage` calls
     `processMessageAndReset(ndefData)`.

If `newLength == 0` when header is written, this is treated as initialization;
`expectedNdefLength` is set to 0 and no message is processed at that point.

#### 5.1.2 Effect of `processMessageAndReset`

When a complete message is detected:

1. A slice of `ndefData` is copied:

   - If `expectedNdefLength > 0` and `expectedNdefLength + 2 <= ndefData.length`,
     `copyLength = expectedNdefLength + 2`.
   - Else, `copyLength = ndefData.length`.
   - `ndefCopy = Arrays.copyOf(ndefData, copyLength)`.

2. `shouldProcess = stateManager.isProcessIncomingMessages()` is read.

3. A **background thread** is spawned that runs:

   ```java
   messageParser.processReceivedNdefMessage(ndefCopy, shouldProcess);
   stateManager.resetForNextMessage(); // resets length + buffer + timestamps
   ```

4. `handleUpdateBinary` **immediately** returns `0x9000` to the payer,
   regardless of success/failure in the background logic.

### 5.2 NDEF Parsing Rules (Incoming Messages)

Parsing is implemented in `NdefMessageParser`.

1. **Initial framing (Type 4 header):**

   - `processReceivedNdefMessage(byte[] ndefData, boolean processIncomingMessages)`:

     - If `processIncomingMessages == false`: the message is ignored.
     - Otherwise:
       - If `ndefData.length < 2`: invalid, ignored.
       - `totalLength = ((ndefData[0] & 0xFF) << 8) | (ndefData[1] & 0xFF)`.
       - If `totalLength <= 0`: invalid, ignored.
       - If `totalLength + 2 > ndefData.length`: invalid/incomplete, ignored.
       - `offset = 2`; `parseNdefRecord(ndefData, offset)` is called.

2. **Record parsing (`parseNdefRecord`)**:

   - Reads one **single NDEF record** starting at `offset`.
   - Header byte: `header = ndefData[offset]`.
   - `typeLength = ndefData[offset + 1] & 0xFF`; must be > 0.
   - SR flag: `isShortRecord = (header & SHORT_RECORD_FLAG) != 0` where
     `SHORT_RECORD_FLAG = 0x10`.

   - If `isShortRecord`:
     - `payloadLength = ndefData[offset + 2] & 0xFF`.
     - `typeFieldStart = offset + 3`.

   - Else (normal record):
     - `payloadLength` is 4‑byte big‑endian at `offset + 2 .. 5`.
     - `typeFieldStart = offset + 6`.

   - `payloadLength` must be > 0.
   - `typeFieldStart + typeLength` must be within `ndefData.length`.
   - `typeField = ndefData[typeFieldStart .. typeFieldStart + typeLength - 1]`.

   - The implementation only accepts **two record types**:
     - **Text** record: `typeLength == 1` and `typeField[0] == 0x54` ("T").
     - **URI** record: `typeLength == 1` and `typeField[0] == 0x55` ("U").

   - If neither condition matches, the message is logged and ignored, and
     no callback is invoked.

3. **Text record decoding (`parseTextRecord`)**:

   - Payload starts at `payloadStart = typeFieldStart + typeLength`.
   - First byte is a **status** byte:
     - Lower 6 bits: language code length.
   - `languageCodeLength = status & 0x3F`.
   - `textStart = payloadStart + 1 + languageCodeLength`.
   - `textLength = payloadLength - 1 - languageCodeLength`.
   - `textBytes = ndefData[textStart .. textStart + textLength - 1]`.
   - `text = new String(textBytes, "UTF-8")`.
   - If a callback is present, `callback.onNdefMessageReceived(text)` is invoked.

4. **URI record decoding (`parseUriRecord`)**:

   - Payload starts at `payloadStart = typeFieldStart + typeLength`.
   - First byte is **URI identifier code** `uriIdentifierCode`.
   - `uriStart = payloadStart + 1`.
   - `uriLength = payloadLength - 1`.
   - `uriBytes = ndefData[uriStart .. uriStart + uriLength - 1]`.
   - `uri = new String(uriBytes, "UTF-8")`.
   - A prefix is obtained from `NdefUriProcessor.getUriPrefix(uriIdentifierCode)`,
     mapping codes 0x00–0x23 to strings like `"https://"`, `"http://www."`, etc.
   - `fullUri = prefix + uri`.
   - If a callback is present, `callback.onNdefMessageReceived(fullUri)` is invoked.

Thus, the **only NDEF messages that drive Cashu logic** are those where the first
(record) is either a Text record or a URI record.

### 5.3 Cashu Token Extraction Rules

`NdefHostCardEmulationService.onNdefMessageReceived(String message)` receives the
`text` or `fullUri` from the parser and passes it into
`CashuPaymentHelper.extractCashuToken(String text)`.

A string is treated as a **Cashu token** if any of the following holds
(see `CashuPaymentHelper`):

1. The entire string is already a token:
   - `text` starts with `"cashuA"` or `"cashuB"`.

2. URL fragment form:
   - `text` contains `"#token=cashu"`.
   - `tokenStart = indexOf("#token=cashu")`.
   - `cashuStart = tokenStart + 7` (this points to the `c` in `cashu` in this code).
   - `cashuEnd = text.length()`.
   - `token = text.substring(cashuStart, cashuEnd)`.

3. URL parameter form:
   - `text` contains `"token=cashu"`.
   - `tokenStart = indexOf("token=cashu")`.
   - `cashuStart = tokenStart + 6`.
   - `cashuEnd` is the first of `&` or `#` after `cashuStart` or end of string.
   - `token = text.substring(cashuStart, cashuEnd)`.

4. Free‑text form:
   - Search for substrings `"cashuA"` or `"cashuB"` anywhere in `text`.
   - When found at `tokenIndex`, take the substring

     ```text
     endIndex = first index ≥ tokenIndex where char is
                whitespace or one of '"', '\'', '<', '>', '&', '#',
                or end of string.
     token = text.substring(tokenIndex, endIndex)
     ```

If none of these cases match, no token is extracted and the payment is not
processed.

---

## 6. Payer‑Side Protocol: Exact APDU Sequences

This section consolidates the behavior above into the exact sequences a **payer**
should send to interact with this implementation.

### 6.1 Reading the Cashu Payment Request

Assume the PoS has already called `setPaymentRequest(paymentRequest, amount)` and
thus prepared an outgoing NDEF message.

1. **SELECT NDEF Application**

   ```text
   00 A4 04 00 07 D2 76 00 00 85 01 01 00
   → 90 00
   ```

2. **SELECT CC File (optional, for discovery)**

   ```text
   00 A4 00 0C 02 E1 03
   → 90 00

   00 B0 00 00 0F
   → 00 0F 20 00 3B 00 34 04 06 E1 04 70 FF 00 00 90 00
   ```

   From this, confirm:
   - CC length 15
   - NDEF File ID `E1 04`
   - Max NDEF size `0x70FF`

3. **SELECT NDEF File**

   ```text
   00 A4 00 0C 02 E1 04
   → 90 00
   ```

   At this moment, the PoS sets `selectedFile` to the synthesized Type 4 NDEF
   file containing:

   ```text
   [NLEN high][NLEN low][NDEF Text record bytes...]
   ```

4. **READ NDEF Header (NLEN)**

   ```text
   00 B0 00 00 02
   → [NLEN high][NLEN low] 90 00
   ```

   Example: if NLEN = 0x0030 (48 bytes), response is:

   ```text
   00 30 90 00
   ```

5. **READ NDEF Body (one or more chunks)**

   - Total file length to read = `NLEN` bytes at offset 2.
   - Any `Le` from 1..255 is accepted as long as `offset + Le <= selectedFile.length`.

   Example for NLEN = 0x0030 (48 bytes):

   ```text
   00 B0 00 02 30
   → [48 bytes of NDEF record] 90 00
   ```

The payer then parses the NDEF message:

- NDEF file framing: Type 4 header (already known from NLEN).
- Single Text record with language `"en"` and text = Cashu payment request string
  (`"creqA..."`).

### 6.2 Writing a Cashu Token Back to the PoS

To send back a token, the payer must write a **new NDEF file** into the PoS’
receive buffer via UPDATE BINARY.

**Precondition:** `setPaymentRequest(...)` has enabled `processIncomingMessages`.

1. **Ensure NDEF File is Selected**

   The PoS only allows UPDATE BINARY if `selectedFile != null` and is not equal to
   the CC file. This is normally satisfied by performing:

   ```text
   00 A4 04 00 07 D2 76 00 00 85 01 01 00   (SELECT AID)
   → 90 00

   00 A4 00 0C 02 E1 04                     (SELECT NDEF file)
   → 90 00
   ```

2. **Construct an NDEF message containing the token**

   The PoS will only process:

   - A single NDEF **Text** record, or
   - A single NDEF **URI** record

   whose decoded content (string) contains a Cashu token according to the
   extraction rules in 5.3.

   **Examples that will be accepted:**

   - Text record with `payload = "cashuB..."`.
   - Text record with `payload = "https://cashu.me/#token=cashuB..."`.
   - URI record with `identifierCode = 0x04` ("https://") and `uri =
     "cashu.me/#token=cashuB..."` so that `fullUri` becomes
     `"https://cashu.me/#token=cashuB..."`.

   The NDEF record must then be wrapped in Type 4 framing:

   ```text
   full[0] = (NLEN >> 8) & 0xFF
   full[1] = NLEN & 0xFF
   full[2..] = one NDEF record (Text or URI)
   ```

3. **Write NLEN and Body** – valid patterns

   The implementation accepts **two main patterns** for writing a complete NDEF
   message into `ndefData`:

   #### Pattern A: Header (NLEN) first, then body in one or more chunks

   1. Write NLEN at offset 0:

      ```text
      00 D6 00 00 02 [NLEN high][NLEN low]
      → 90 00
      ```

   2. Write body bytes starting at offset 2 using as many UPDATE BINARY APDUs as
      desired, provided `offset + Lc <= 65536` each time.

      For example, if NLEN = 0x0064 (100 bytes):

      ```text
      00 D6 00 02 34 [first 52 bytes]
      → 90 00

      00 D6 00 36 34 [next 52 bytes]
      → 90 00
      ```

      Once the last UPDATE satisfies `(offset + Lc) >= (NLEN + 2)`,
      `processMessageAndReset` is triggered.

   #### Pattern B: “Set NLEN=0, then body, then final NLEN”

   1. Set NLEN to 0 at offset 0:

      ```text
      00 D6 00 00 02 00 00
      → 90 00
      ```

   2. Write the body starting at offset 2 as in Pattern A.

   3. Finally write the **real NLEN** at offset 0:

      ```text
      00 D6 00 00 02 [NLEN high][NLEN low]
      → 90 00
      ```

      At this point:

      - `newLength > 0`.
      - The body region `[2 .. NLEN+1]` contains non‑zero data.
      - `hasNonZeroData` returns true.
      - `processMessageAndReset` is invoked and the NDEF is parsed.

4. **Resulting Processing on PoS**

- The parsed Text/URI content string is passed to
  `CashuPaymentHelper.extractCashuToken`.
- If a token is found and validated, `redeemToken` is called, and on success the
  PoS invokes `paymentCallback.onCashuTokenReceived(redeemedToken)`.
- On validation or redemption failure, `paymentCallback.onCashuPaymentError(...)`
  is invoked and the internal state is cleared via `clearPaymentRequest()`.

After a successful token processing, `NdefHostCardEmulationService` calls
`ndefProcessor.setProcessIncomingMessages(false)`, so further incoming NDEF
messages will be ignored until a new payment request is started.

---

## 7. Summary

From the **payer (reader)** perspective, the exact protocol to interoperate with
this implementation is:

1. **Select the NDEF application** with the AID `D2 76 00 00 85 01 01` using the
   APDU `00 A4 04 00 07 D2 76 00 00 85 01 01 00`.
2. Optionally **read the CC file** at file ID `E1 03` to discover that the NDEF
   file ID is `E1 04` and the advertised size and MLe/MLc.
3. **Select the NDEF file** (`00 A4 00 0C 02 E1 04`) and **READ BINARY** to obtain
   the payment request NDEF (Text record with `"en"` + Cashu `creqA...` string).
4. Complete the Cashu payment off‑tag.
5. **Select the NDEF file again (if needed)** and **UPDATE BINARY** to write a
   complete Type 4 NDEF file containing a single Text or URI record from which a
   Cashu token can be extracted, using either:
   - NLEN first then body, or
   - NLEN=0, then body, then final NLEN.
6. The PoS processes this message asynchronously and responds to all valid UPDATE
   BINARY APDUs with `90 00` regardless of payment outcome.

This document is a direct formalization of the current implementation and avoids
any speculative or“recommended” behavior beyond what the code actually does.
