import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
fun main() {
    val a = Amount.parse("150 mxn")
    println(a)
}
