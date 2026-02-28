import java.io.BufferedReader
import java.io.InputStream
import java.io.StringReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val host = "0.0.0.0"
    var port = args.find { arg : String -> arg === "PORT" }?.get(0)?.digitToIntOrNull()
    if (port == null) {
        println("No port specified starting on port 3000")
        port = 3000
    }
    println("Listening on port: ${port}")

    val socket = ServerSocket(port)

    var running = true

    while (running) {
        val client = socket.accept()
        thread {
            handleConnection(client)
        }

    }
}

fun handleConnection(client: Socket) {
    var buffer = ByteArray(8192)

    val clientInput = client.getInputStream()
    val clientOutput = client.getOutputStream()

    val ( headerBytes, remainingBytes ) = readAllBytes(clientInput, buffer)

    if (headerBytes.isEmpty()) {
        println("Error Reading Headers")
        client.close()
        return
    }

    val data = String(headerBytes, charset = StandardCharsets.UTF_8)
    val parsedRequest = parseHeaders(data)

}

fun readAllBytes(stream: InputStream, buffer: ByteArray): Pair<ByteArray, ByteArray> {
    var accumulator = byteArrayOf()

    var bytesRead = stream.read(buffer)
    while (bytesRead != -1) {
        accumulator += buffer.sliceArray(0..bytesRead - 1)
        val endIndex = getHeaderEnd(accumulator)
        if (endIndex != -1) {
            val headerBytes = accumulator.copyOfRange(0, endIndex)
            val remainingBytes = accumulator.copyOfRange(endIndex, accumulator.size)
            return Pair(headerBytes, remainingBytes)
        }
        bytesRead = stream.read(buffer)
    }
    return Pair(accumulator, accumulator)
}

fun getHeaderEnd(bytes: ByteArray): Int {
    for (i in 0..bytes.lastIndex - 3) {
        if (
            bytes[i].toInt() == 0x0D &&
            bytes[i + 1].toInt() == 0x0A &&
            bytes[i + 2].toInt() == 0x0D &&
            bytes[i + 3].toInt() == 0x0A)
        {
            return i + 4
        }
    }
    return -1
}

fun parseHeaders(request: String): Request {
    val reader = BufferedReader(StringReader(request))

    val requestLine = reader.readLine()
    val requestLineParts = requestLine.split(" ")
    if (requestLineParts.size != 3) {
        throw IllegalArgumentException("Bad request line format")
    }
    val (method, path, protocol) = requestLineParts

    var lastLine: String? = null
    var currentLine = reader.readLine()
    val headers = mutableMapOf<String , String>()

    while (!currentLine.isNullOrEmpty()) {
        lastLine = currentLine
        val keyVal = lastLine.split(": ", limit = 2)
        headers[keyVal[0]] = keyVal[1]

        currentLine = reader.readLine()
    }
    return Request(method, headers, path, protocol)
}
