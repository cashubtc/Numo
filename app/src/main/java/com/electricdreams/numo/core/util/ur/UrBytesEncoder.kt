package com.electricdreams.numo.core.util.ur

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Minimal definite-length CBOR writer: byte strings, unsigned ints and arrays.
 * Matches the canonical (shortest-form) encoding produced by the `cborg` JS library,
 * which the reference bc-ur implementation uses.
 */
internal object Cbor {

    fun byteString(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeTypeAndLength(out, MAJOR_BYTE_STRING, data.size.toLong())
        out.write(data)
        return out.toByteArray()
    }

    fun uint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeTypeAndLength(out, MAJOR_UINT, value)
        return out.toByteArray()
    }

    fun array(items: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        writeTypeAndLength(out, MAJOR_ARRAY, items.size.toLong())
        items.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun writeTypeAndLength(out: ByteArrayOutputStream, majorType: Int, length: Long) {
        val base = majorType shl 5
        when {
            length < 24 -> out.write(base or length.toInt())
            length <= 0xFF -> {
                out.write(base or 24)
                out.write(length.toInt() and 0xFF)
            }
            length <= 0xFFFF -> {
                out.write(base or 25)
                out.write((length shr 8).toInt() and 0xFF)
                out.write(length.toInt() and 0xFF)
            }
            else -> {
                out.write(base or 26)
                out.write((length shr 24).toInt() and 0xFF)
                out.write((length shr 16).toInt() and 0xFF)
                out.write((length shr 8).toInt() and 0xFF)
                out.write(length.toInt() and 0xFF)
            }
        }
    }

    private const val MAJOR_UINT = 0
    private const val MAJOR_BYTE_STRING = 2
    private const val MAJOR_ARRAY = 4
}

/**
 * bc-bytewords "minimal" encoding: every byte maps to the first and last letter of its
 * four-letter word, and a CRC32 checksum of the payload is appended before encoding.
 */
internal object Bytewords {

    /** The 256 four-letter bytewords from the bc-bytewords spec, concatenated. */
    private const val WORDS =
        "ableacidalsoapexaquaarchatomaunt" + "awayaxisbackbaldbarnbeltbetabias" +
        "bluebodybragbrewbulbbuzzcalmcash" + "catschefcityclawcodecolacookcost" +
        "cruxcurlcuspcyandarkdatadaysdeli" + "dicedietdoordowndrawdropdrumdull" +
        "dutyeacheasyechoedgeepicevenexam" + "exiteyesfactfairfernfigsfilmfish" +
        "fizzflapflewfluxfoxyfreefrogfuel" + "fundgalagamegeargemsgiftgirlglow" +
        "goodgraygrimgurugushgyrohalfhang" + "hardhawkheathelphighhillholyhope" +
        "hornhutsicedideaidleinchinkyinto" + "irisironitemjadejazzjoinjoltjowl" +
        "judojugsjumpjunkjurykeepkenokept" + "keyskickkilnkingkitekiwiknoblamb" +
        "lavalazyleaflegsliarlimplionlist" + "logoloudloveluaulucklungmainmany" +
        "mathmazememomenumeowmildmintmiss" + "monknailnavyneednewsnextnoonnote" +
        "numbobeyoboeomitonyxopenovalowls" + "paidpartpeckplaypluspoempoolpose" +
        "puffpumapurrquadquizracerampreal" + "redorichroadrockroofrubyruinruns" +
        "rustsafesagascarsetssilkskewslot" + "soapsolosongstubsurfswantacotask" +
        "taxitenttiedtimetinytoiltombtoys" + "triptunatwinuglyundouniturgeuser" +
        "vastveryvetovialvibeviewvisavoid" + "vowswallwandwarmwaspwavewaxywebs" +
        "whatwhenwhizwolfworkyankyawnyell" + "yogayurtzapszerozestzinczonezoom"

    init {
        require(WORDS.length == 256 * 4) { "bytewords table must contain 256 words" }
    }

    fun encodeMinimal(data: ByteArray): String {
        val withCrc = data + crc32Bytes(data)
        val sb = StringBuilder(withCrc.size * 2)
        for (b in withCrc) {
            val wordStart = (b.toInt() and 0xFF) * 4
            sb.append(WORDS[wordStart]).append(WORDS[wordStart + 3])
        }
        return sb.toString()
    }

    fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    private fun crc32Bytes(data: ByteArray): ByteArray {
        val value = crc32(data)
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )
    }
}

/**
 * Encodes a message as an animated `ur:bytes` sequence, compatible with the
 * `@gandlaf21/bc-ur` implementation used by cashu.me and other Cashu wallets.
 *
 * The message is CBOR-wrapped as a byte string, split into equal fragments (the last
 * one zero-padded), and each call to [nextPart] returns the next frame string, cycling
 * through the pure fragments `UR:BYTES/{seqNum}-{seqLength}/{body}` forever.
 * Fountain (Luby) mixed parts are intentionally not emitted: UR decoders reassemble
 * the message from repeated pure fragments alone.
 *
 * Frame strings are uppercased so QR encoders can use the denser alphanumeric mode;
 * UR decoders lowercase their input before parsing.
 */
class UrBytesEncoder @JvmOverloads constructor(
    message: ByteArray,
    maxFragmentLength: Int = DEFAULT_MAX_FRAGMENT_LENGTH,
    minFragmentLength: Int = DEFAULT_MIN_FRAGMENT_LENGTH,
) {
    private val cborMessage: ByteArray = Cbor.byteString(message)
    private val checksum: Long = Bytewords.crc32(cborMessage)
    private val fragmentLength: Int =
        findNominalFragmentLength(cborMessage.size, minFragmentLength, maxFragmentLength)
    private val fragments: List<ByteArray> = partitionMessage(cborMessage, fragmentLength)

    private var seqNum = 0

    /** Total number of fragments the message was split into. */
    val fragmentCount: Int get() = fragments.size

    /** Length of the CBOR-wrapped message in bytes. */
    val messageLength: Int get() = cborMessage.size

    /** 1-based index of the fragment returned by the most recent [nextPart] call. */
    val currentSeqNum: Int get() = seqNum

    val isSinglePart: Boolean get() = fragments.size == 1

    /** Returns the next frame string, cycling through fragments 1..[fragmentCount]. */
    fun nextPart(): String {
        if (isSinglePart) {
            return "UR:BYTES/" + Bytewords.encodeMinimal(cborMessage).uppercase()
        }
        seqNum = (seqNum % fragmentCount) + 1
        val partCbor = Cbor.array(
            listOf(
                Cbor.uint(seqNum.toLong()),
                Cbor.uint(fragmentCount.toLong()),
                Cbor.uint(messageLength.toLong()),
                Cbor.uint(checksum),
                Cbor.byteString(fragments[seqNum - 1]),
            )
        )
        return "UR:BYTES/$seqNum-$fragmentCount/" + Bytewords.encodeMinimal(partCbor).uppercase()
    }

    companion object {
        const val DEFAULT_MAX_FRAGMENT_LENGTH = 150
        const val DEFAULT_MIN_FRAGMENT_LENGTH = 10

        /**
         * Smallest fragment length such that the whole message splits into fragments of
         * at most [maxFragmentLength] bytes (port of bc-ur `findNominalFragmentLength`).
         */
        internal fun findNominalFragmentLength(
            messageLength: Int,
            minFragmentLength: Int,
            maxFragmentLength: Int,
        ): Int {
            require(messageLength > 0 && minFragmentLength > 0 && maxFragmentLength >= minFragmentLength) {
                "invalid fragment or message length"
            }
            val maxFragmentCount = (messageLength + minFragmentLength - 1) / minFragmentLength
            for (fragmentCount in 1..maxFragmentCount) {
                val fragmentLength = (messageLength + fragmentCount - 1) / fragmentCount
                if (fragmentLength <= maxFragmentLength) return fragmentLength
            }
            return minFragmentLength
        }

        /** Splits [message] into [fragmentLength]-sized fragments, zero-padding the last one. */
        internal fun partitionMessage(message: ByteArray, fragmentLength: Int): List<ByteArray> {
            val fragments = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < message.size) {
                val end = minOf(offset + fragmentLength, message.size)
                val fragment = ByteArray(fragmentLength)
                System.arraycopy(message, offset, fragment, 0, end - offset)
                fragments.add(fragment)
                offset = end
            }
            return fragments
        }
    }
}
