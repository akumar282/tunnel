import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread
import kotlin.time.Clock

val cores = Runtime.getRuntime().availableProcessors()
val connectionQueue = ArrayBlockingQueue<Socket>(2000, true)
val DEFAULT_PORT = 3000

fun main(args: Array<String>) {
    val workers = cores * 4

    var port = DEFAULT_PORT

    for (arg in args) {
        if (arg.startsWith("--port=")) {
            port = arg.split('=')[1].toInt()
        }
    }

    if (port == DEFAULT_PORT) {
        println("No port specified starting on port 3000")
    }

    println("Listening on port: $port")

    val socket = ServerSocket(port)

    for (i in 0 until workers) {
        thread {
            workerHandler()
        }
    }

    val running = true
    while (running) {
        val client = socket.accept()
        connectionQueue.put(client)

    }
}

fun workerHandler() {
    try {
        while (true) {
            try {
                val socket = connectionQueue.take()
                handleConnection(socket)
            } catch (e: Error) {
                println(e)
                continue
            }
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

fun handleConnection(client: Socket) {
    val buffer = ByteArray(8192)

    val clientInput = client.getInputStream()
    val clientOutput = client.getOutputStream()

    try {
        val requestLine = readRequestLine(clientInput)
        if (requestLine.isNullOrBlank()) {
            client.close()
            return
        }

        val validMethods = setOf("GET", "POST", "CONNECT", "HEAD", "PUT", "DELETE")

        val isValid = validMethods.any { requestLine.startsWith(it) }

        if (!isValid) {
            client.close()
            return
        }

        val parts = requestLine.trim().split(" ")
        if (parts.size < 3) {
            client.close()
            return
        }

        val method = parts[0]
        val path = parts[1]
        val protocol = parts[2]

        if (method == "CONNECT") {
            val (host, port) = parseConnectTarget(path)
            discardConnectHeaders(clientInput)

            connectRequest(clientInput, clientOutput, host, port, client)
            return
        }

        val (headerBytes, remainingBytes) = readAllBytes(clientInput, buffer)

        val fullRequest = requestLine + "\r\n" + String(headerBytes, Charsets.UTF_8)
        val parsedRequest = parseHeaders(fullRequest)

        val (host, port, newPath) = getHostPort(parsedRequest.path)
        parsedRequest.path = newPath

        val targetSocket = Socket(host, port)
        targetSocket.soTimeout = 15000
        client.soTimeout = 15000

        val targetInput = targetSocket.getInputStream()
        val targetOutput = targetSocket.getOutputStream()

        println("${parsedRequest.method} ${host}:${port} -> HTTP/1.1 200 Request Forwarded")

        val newRequestHeader = reconstructHeadersToBytes(parsedRequest)
        targetOutput.write(newRequestHeader)

        if (remainingBytes.isNotEmpty()) {
            targetOutput.write(remainingBytes)
        }

        managePipes(
            targetInput,
            targetOutput,
            clientInput,
            clientOutput,
            client,
            targetSocket
        )

    } catch (e: Exception) {
        println("Connection error: $e")
        client.close()
    }
}

fun connectRequest(input: InputStream, output: OutputStream, host: String, port: Int, socket: Socket) {
    val targetSocket = Socket(host, port)
    val targetInput = targetSocket.getInputStream()
    val targetOutput = targetSocket.getOutputStream()

    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(charset = Charsets.UTF_8))
    output.flush()

    val time = Clock.System.now()
    println("[${time}] CONNECT ${host}:${port} ${socket.remoteSocketAddress} -> HTTP/1.1 200 Connection Established")

    managePipes(
        targetInput,
        targetOutput,
        input,
        output,
        socket,
        targetSocket
    )

}

@OptIn(ExperimentalAtomicApi::class)
fun managePipes(
    targetInputStream: InputStream,
    targetOutputStream: OutputStream,
    clientInputStream: InputStream,
    clienOutputStream: OutputStream,
    client: Socket,
    target: Socket
) {
    val runningFlag = AtomicBoolean(true)

    val giveOutput = thread {
        pipe(clientInputStream, targetOutputStream)
        if (runningFlag.compareAndSet(expectedValue = true, newValue = false)) {
            target.close()
            client.close()
        }
    }

    val readInput = thread {
        pipe(targetInputStream, clienOutputStream)
        if (runningFlag.compareAndSet(expectedValue = true, newValue = false)) {
            target.close()
            client.close()
        }
    }

    readInput.join()
    giveOutput.join()
}

fun pipe(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(8192)
    try {
        var bytesRead = input.read(buffer)
        while (bytesRead != -1) {
            output.write(buffer, 0, bytesRead)
            bytesRead = input.read(buffer)
        }
    } catch (_: SocketException) {
        return
    } catch (e: IOException) {
        println("Unexpected error $e")
    }
}

fun readAllBytes(stream: InputStream, buffer: ByteArray): Pair<ByteArray, ByteArray> {
    val accumulator = ByteArrayOutputStream()

    var bytesRead = stream.read(buffer)
    var prevSize: Int = accumulator.size()
    while (bytesRead != -1) {
        accumulator.write(buffer, 0, bytesRead)
        val currentSize = accumulator.size()
        val searchStart = 0.coerceAtLeast(prevSize - 3)
        val currByteArray = accumulator.toByteArray()
        val endIndex = getHeaderEnd(currByteArray, currentSize, searchStart)
        prevSize = currentSize
        if (endIndex != -1) {
            val headerBytes = currByteArray.copyOfRange(0, endIndex)
            val remainingBytes = currByteArray.copyOfRange(endIndex, currByteArray.size)
            return Pair(headerBytes, remainingBytes)
        }
        bytesRead = stream.read(buffer)
    }
    return Pair(accumulator.toByteArray(), accumulator.toByteArray())
}

fun getHeaderEnd(bytes: ByteArray, searchEnd: Int, searchStart: Int): Int {
    for (i in searchStart until searchEnd - 3) {
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

    var lastLine: String?
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

fun readRequestLine(stream: InputStream): String? {
    val sb = StringBuilder()
    var prev = -1
    while (true) {
        val curr = stream.read()
        if (curr == -1) return null

        sb.append(curr.toChar())

        if (prev == '\r'.code && curr == '\n'.code) {
            return sb.toString().trim()
        }
        prev = curr
    }
}

fun parseConnectTarget(target: String): Pair<String, Int> {
    val parts = target.split(":", limit = 2)
    val host = parts[0]
    val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 443 else 443
    return Pair(host, port)
}

fun discardConnectHeaders(stream: InputStream) {
    var state = 0
    while (true) {
        val curr = stream.read()
        if (curr == -1) {
            return
        }

        state = when (state) {
            0 -> if (curr == '\r'.code) 1 else 0
            1 -> if (curr == '\n'.code) 2 else 0
            2 -> if (curr == '\r'.code) 3 else 0
            3 -> if (curr == '\n'.code) return else 0
            else -> 0
        }
    }
}

fun getHostPort(destination: String): Triple<String, Int, String> {
    var port: Int
    var host: String
    var newPath: String

    if (destination.startsWith("https")) {
        val result = assignPathAndPort(destination, "https://", 443)
        port = result.first
        newPath = result.second
        host = result.third

    } else if (destination.startsWith("http")) {
        val result = assignPathAndPort(destination, "http://", 80)
        port = result.first
        newPath = result.second
        host = result.third

    } else {
        val result = assignPathAndPort(destination, "/", 80)
        port = result.first
        newPath = result.second
        host = result.third

    }
    return Triple(host, port, newPath)
}

fun reconstructHeadersToBytes(request: Request): ByteArray {
    var headerString = request.method + " " + request.path + " " + request.protocol + "\r\n"
    for (header in request.headers) {
        headerString += header.key + ": " + header.value + "\r\n"
    }
    headerString += "\r\n"
    return headerString.toByteArray(charset = Charsets.UTF_8)
}

fun assignPathAndPort(destination: String, remove: String, fallbackPort: Int): Triple<Int, String, String> {
    val data = destination.split(remove, limit = 2)
    val hostPort = data[0]
    val port: Int
    val newPath: String
    val host: String

    if (data.size == 1) {
        newPath = "/"
    } else {
        newPath = "/" + data.lastOrNull()
    }

    val splitHostPort = hostPort.split(':', limit = 2)

    host = splitHostPort[0]

    if (splitHostPort.size == 1) {
        port = fallbackPort
    } else {
        port = splitHostPort[1].toInt()
    }
    return Triple(port, newPath, host)
}
