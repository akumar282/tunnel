data class Request(
    val method: String,
    val headers: Map<String, String>,
    val path: String,
    val protocol: String,
    var body: String? = null
)


