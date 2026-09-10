package example

object TestMain {
  def main(args: Array[String]): Unit = assert(TestAccess.value() == 9)
}
