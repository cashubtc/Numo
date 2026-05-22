import org.cashudevkit.CurrencyUnit
fun main() {
    val usd = CurrencyUnit.Usd
    val custom = CurrencyUnit.Custom("mxn")
    println(usd)
    println(custom)
}
