import rikka.shizuku.Shizuku

fun main() {
    for (m in Shizuku::class.java.methods) {
        println(m.name)
    }
}
