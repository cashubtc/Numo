import com.electricdreams.numo.core.model.Amount

fun main() {
    val a = Amount.parse("1,000 sat")
    println(a)
}
