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

    val ( headerBytes, remainingBytes ) = readAllBytes(clientInput, buffer)

    if (headerBytes.isEmpty()) {
        val time = Clock.System.now()
        println("[${time}] ${client.remoteSocketAddress}:${client.port} -> HTTP/1.1 200 Empty Connection")
        client.close()
        return
    }

    val data = String(headerBytes, charset = StandardCharsets.UTF_8)
    val parsedRequest = parseHeaders(data)


    val (host, port, newPath) = getHostPort(parsedRequest.path)
    parsedRequest.path = newPath

    if (parsedRequest.method == "CONNECT") {
        try {
            connectRequest(clientInput, clientOutput, host, port, client)
        } catch (e: UnknownHostException) {
            println("Invalid host: $host")
            client.close()
            return
        }
        return
    } else {
        val targetSocket = Socket(host, port)
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