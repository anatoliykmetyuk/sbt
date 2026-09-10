package example

object Main {
  def main(args: Array[String]): Unit = {
    assert(Reader.read == args(0).toInt)
    assert(ByteArrayAccess.getInt() > 0)
    assert(JavaCaller.value() == 7)
    assert(JavaValue.value() == 5)
    assert(Exported.value == 3)
  }
}
