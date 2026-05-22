import org.cashudevkit.createBip321Uri

fun main() {
    val uri = createBip321Uri("creq1...", null, null)
    println(uri)
}
